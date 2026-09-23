/**
 * PhotonSession owns cache connection recovery and the asynchronous request API.
 *
 * Initial connection attempts are bounded so client creation fails loudly.
 * After a live connection is lost, reconnect attempts continue in the
 * background until one succeeds or the session closes. Requests that have not
 * been sent may use the origin fallback window while no connector is available.
 */
import { performance } from "node:perf_hooks";

import type { PhotonConnector } from "./connection.js";
import {
  PhotonCacheUnavailable,
  PhotonConfigError,
  PhotonConnectionError,
  PhotonError,
} from "./errors.js";
import type { PhotonRequest, ResolvedConfig, PhotonResponse } from "./models.js";
import {
  parseConfigResponse,
  parseRequestResponse,
  serializeConfig,
  serializeRequest,
} from "./protocol.js";
import { PredicateEvaluator } from "./predicates.js";

const DEFAULT_CONNECT_ATTEMPTS = 3;
const DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
const DEFAULT_BASE_DELAY_MS = 100;
const DEFAULT_MAX_DELAY_MS = 3_200;
const DEFAULT_JITTER = 0.2;
const MAX_TIMER_DELAY_MS = 2_147_483_647;

/** Reconnect and origin-fallback settings. */
export interface ReconnectPolicyOptions {
  /**
   * Origin fallback window for each outage, in milliseconds.
   *
   * `null`, `undefined`, or a negative value means indefinitely. Zero disables
   * fallback, and a positive value allows fallback until that deadline.
   */
  fallbackDurationMs?: number | null;
  /** Maximum attempts while initially opening the session. Defaults to 3. */
  connectAttempts?: number;
  /** Overall timeout for each initial connect, AUTH, and config attempt. */
  connectionTimeoutMs?: number;
  /** Initial reconnect delay in milliseconds. Defaults to 100. */
  baseDelayMs?: number;
  /** Maximum reconnect delay before jitter. Defaults to 3,200. */
  maxDelayMs?: number;
  /** Symmetric jitter ratio in `[0, 1)`. Defaults to 0.2. */
  jitter?: number;
}

interface ReconnectPolicy {
  fallbackWindowMs: number;
  connectAttempts: number;
  connectionTimeoutMs: number;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: number;
}

export type ConnectionState = "init" | "alive" | "reconnecting" | "closed";
type SessionState = "created" | "open" | "closed";
export type PhotonConnectorFactory = () => PhotonConnector;

function timerDelay(value: number, name: string): number {
  if (!Number.isFinite(value) || value <= 0 || value > MAX_TIMER_DELAY_MS) {
    throw new PhotonConfigError(
      `${name} must be greater than zero and at most ${MAX_TIMER_DELAY_MS}`,
    );
  }
  return value;
}

function positiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 1) {
    throw new PhotonConfigError(`${name} must be a positive integer`);
  }
  return value;
}

function fallbackWindow(value: number | null | undefined): number {
  if (value === null || value === undefined) return Number.POSITIVE_INFINITY;
  if (!Number.isSafeInteger(value)) {
    throw new PhotonConfigError(
      "fallbackDurationMs must be a whole number of milliseconds, null, or undefined",
    );
  }
  return value < 0 ? Number.POSITIVE_INFINITY : value;
}

function resolveReconnectPolicy(
  options: ReconnectPolicyOptions | undefined,
  reconnectable: boolean,
): ReconnectPolicy {
  const baseDelayMs = timerDelay(options?.baseDelayMs ?? DEFAULT_BASE_DELAY_MS, "baseDelayMs");
  const maxDelayMs = timerDelay(options?.maxDelayMs ?? DEFAULT_MAX_DELAY_MS, "maxDelayMs");
  if (maxDelayMs < baseDelayMs) {
    throw new PhotonConfigError("maxDelayMs must be at least baseDelayMs");
  }
  const jitter = options?.jitter ?? DEFAULT_JITTER;
  if (!Number.isFinite(jitter) || jitter < 0 || jitter >= 1) {
    throw new PhotonConfigError("jitter must be in [0, 1)");
  }
  return {
    fallbackWindowMs: fallbackWindow(options?.fallbackDurationMs),
    connectAttempts: positiveInteger(
      options?.connectAttempts ?? (reconnectable ? DEFAULT_CONNECT_ATTEMPTS : 1),
      "connectAttempts",
    ),
    connectionTimeoutMs: timerDelay(
      options?.connectionTimeoutMs ?? DEFAULT_CONNECTION_TIMEOUT_MS,
      "connectionTimeoutMs",
    ),
    baseDelayMs,
    maxDelayMs,
    jitter,
  };
}

/** Parse a host:port endpoint, preserving colons before the final port separator. */
export function parseEndpoint(endpoint: string): [string, number] {
  const separator = endpoint.lastIndexOf(":");
  if (separator < 1 || separator === endpoint.length - 1) {
    throw new PhotonConfigError(`Invalid endpoint "${endpoint}": expected host:port`);
  }

  const endpointHost = endpoint.slice(0, separator);
  const host =
    endpointHost.startsWith("[") && endpointHost.endsWith("]")
      ? endpointHost.slice(1, -1)
      : endpointHost;
  const portText = endpoint.slice(separator + 1);
  if (host.length === 0) {
    throw new PhotonConfigError(`Invalid endpoint "${endpoint}": host cannot be empty`);
  }
  if (!/^[+-]?\d+$/.test(portText.trim())) {
    throw new PhotonConfigError(`Invalid port in endpoint "${endpoint}"`);
  }

  const port = Number(portText);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new PhotonConfigError(`Port out of range in endpoint "${endpoint}": ${port}`);
  }
  return [host, port];
}

function singleConnectorFactory(connector: PhotonConnector): PhotonConnectorFactory {
  let supplied = false;
  return () => {
    if (supplied) {
      throw new PhotonConnectionError(
        "This session does not have a reconnectable connector factory",
      );
    }
    supplied = true;
    return connector;
  };
}

/** Owns the active cache connection and exposes the asynchronous request API. */
export class PhotonSession {
  private _config: ResolvedConfig | undefined;
  private predicateEvaluator = PredicateEvaluator.compile(undefined);
  private state: SessionState = "created";
  private connection: ConnectionState = "init";
  private readonly connectorFactory: PhotonConnectorFactory;
  private readonly reconnectPolicy: ReconnectPolicy;
  private readonly reconnectable: boolean;
  private connector: PhotonConnector | undefined;
  private candidate: PhotonConnector | undefined;
  private reconnectTask: Promise<void> | undefined;
  private fallbackDeadline = 0;
  private readonly disconnected = new WeakSet<PhotonConnector>();
  private readonly recoveryAbort = new AbortController();

  constructor(
    connectorOrFactory: PhotonConnector | PhotonConnectorFactory,
    private readonly overrides: Readonly<Record<string, unknown>> = {},
    reconnectPolicy?: ReconnectPolicyOptions,
  ) {
    this.reconnectable = typeof connectorOrFactory === "function";
    this.connectorFactory = this.reconnectable
      ? (connectorOrFactory as PhotonConnectorFactory)
      : singleConnectorFactory(connectorOrFactory as PhotonConnector);
    this.reconnectPolicy = resolveReconnectPolicy(reconnectPolicy, this.reconnectable);
  }

  get config(): ResolvedConfig | undefined {
    return this._config;
  }

  get isOpen(): boolean {
    return this.state === "open";
  }

  get connectionState(): ConnectionState {
    return this.connection;
  }

  /** Open the initial connection with bounded retries. */
  async open(): Promise<void> {
    if (this.state !== "created") {
      throw new PhotonError(`open() called on a ${this.state} session`);
    }

    let lastFailure: unknown = new PhotonConnectionError("Photon connection attempts exhausted");
    for (let attempt = 1; attempt <= this.reconnectPolicy.connectAttempts; attempt++) {
      if (this.state !== "created") {
        throw new PhotonConnectionError("Session closed during open()");
      }

      let candidate: PhotonConnector | undefined;
      try {
        candidate = this.createCandidate();
        const initialized = await this.initializeWithTimeout(
          candidate,
          this.reconnectPolicy.connectionTimeoutMs,
        );
        if (this.state !== "created") {
          throw new PhotonConnectionError("Session closed during open()");
        }
        this.publishConnection(candidate, initialized);
        this.candidate = undefined;
        this.state = "open";
        this.connection = "alive";
        return;
      } catch (error) {
        lastFailure = error;
        await this.closeCandidate(candidate);
        if (
          attempt < this.reconnectPolicy.connectAttempts &&
          !(await this.waitBeforeRetry(attempt))
        ) {
          lastFailure = new PhotonConnectionError("Session closed during open()");
          break;
        }
      }
    }

    this.state = "closed";
    this.connection = "closed";
    if (lastFailure instanceof Error) throw lastFailure;
    throw new PhotonConnectionError("Photon connection attempts exhausted", {
      cause: lastFailure,
    });
  }

  /**
   * Send matching requests to Photon and decline requests that are safe to send
   * to origin.
   *
   * A disconnected request has not been sent, so it may fall back while the
   * per-outage window is open. Once execute starts, every failure propagates:
   * the request may have reached Photon and must not be directly replayed.
   */
  async request(request: PhotonRequest): Promise<PhotonResponse | undefined> {
    if (this.state === "created") {
      throw new PhotonError("request() called on a created session");
    }
    if (this.state === "open" && !this.predicateEvaluator.shouldRouteToPhoton(request)) {
      return undefined;
    }

    let currentConnector = this.connector;
    if (this.state === "open" && currentConnector?.isDead) {
      this.handleDisconnect(currentConnector);
      currentConnector = undefined;
    }
    if (this.state === "open" && currentConnector === undefined) {
      if (performance.now() < this.fallbackDeadline) return undefined;
      throw new PhotonCacheUnavailable(sanitizeEndpoint(request.url));
    }
    if (currentConnector === undefined) {
      throw new PhotonConnectionError("Photon session has no active connection");
    }

    const [command, args] = serializeRequest(request);
    return parseRequestResponse(await currentConnector.execute(command, ...args));
  }

  /** Close active and recovering connectors. Idempotent. */
  async close(): Promise<void> {
    if (this.state === "closed") return;
    this.state = "closed";
    this.connection = "closed";
    this.recoveryAbort.abort();
    const active = this.connector;
    const candidate = this.candidate;
    const reconnectTask = this.reconnectTask;
    candidate?.destroy();
    await Promise.all([active?.close(), candidate?.close(), reconnectTask?.catch(() => undefined)]);
  }

  /** Synchronous Node.js teardown used by AWS SDK client destroy(). */
  destroy(): void {
    if (this.state === "closed") return;
    this.state = "closed";
    this.connection = "closed";
    this.recoveryAbort.abort();
    this.candidate?.destroy();
    this.connector?.destroy();
  }

  private createCandidate(): PhotonConnector {
    const candidate = this.connectorFactory();
    candidate.onDisconnect = () => this.handleDisconnect(candidate);
    this.candidate = candidate;
    return candidate;
  }

  private async initialize(
    connector: PhotonConnector,
  ): Promise<{ config: ResolvedConfig; evaluator: PredicateEvaluator }> {
    await connector.connect();
    await connector.authenticate();
    const [command, args] = serializeConfig(this.overrides);
    const config = parseConfigResponse(await connector.execute(command, ...args));
    return { config, evaluator: PredicateEvaluator.compile(config.raw) };
  }

  private async initializeWithTimeout(
    connector: PhotonConnector,
    timeout: number,
  ): Promise<{ config: ResolvedConfig; evaluator: PredicateEvaluator }> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        this.initialize(connector),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            connector.destroy();
            reject(new PhotonConnectionError(`opening a connection timed out after ${timeout}ms`));
          }, timeout);
        }),
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  }

  private publishConnection(
    connector: PhotonConnector,
    initialized: { config: ResolvedConfig; evaluator: PredicateEvaluator },
  ): void {
    if (this.disconnected.has(connector) || connector.isDead) {
      throw new PhotonConnectionError("Connection closed during initialization");
    }
    this._config = initialized.config;
    this.predicateEvaluator = initialized.evaluator;
    this.connector = connector;
  }

  private handleDisconnect(connector: PhotonConnector): void {
    this.disconnected.add(connector);
    if (this.state !== "open" || this.connector !== connector) return;

    // Open the fallback window before clearing the connector so a request that
    // observes no connector always sees the current outage's deadline.
    this.fallbackDeadline = performance.now() + this.reconnectPolicy.fallbackWindowMs;
    this.connector = undefined;
    this.connection = "reconnecting";
    void connector.close().catch(() => undefined);
    if (this.reconnectable) this.startReconnect();
  }

  private startReconnect(): void {
    if (this.reconnectTask !== undefined || this.state !== "open") return;
    const task = this.reconnectForever();
    this.reconnectTask = task;
    void task.then(
      () => this.releaseReconnectTask(task),
      () => this.releaseReconnectTask(task),
    );
  }

  private async reconnectForever(): Promise<void> {
    let attempt = 0;
    while (this.state === "open" && this.connection === "reconnecting") {
      attempt++;
      let candidate: PhotonConnector | undefined;
      try {
        candidate = this.createCandidate();
        const initialized = await this.initializeWithTimeout(
          candidate,
          this.reconnectPolicy.connectionTimeoutMs,
        );
        if (this.state !== "open" || this.connection !== "reconnecting") {
          await this.closeCandidate(candidate);
          return;
        }
        this.publishConnection(candidate, initialized);
        this.candidate = undefined;
        this.connection = "alive";
        this.fallbackDeadline = 0;
        return;
      } catch {
        await this.closeCandidate(candidate);
        if (!(await this.waitBeforeRetry(attempt))) return;
      }
    }
  }

  private async closeCandidate(candidate: PhotonConnector | undefined): Promise<void> {
    if (candidate === undefined) return;
    try {
      await candidate.close();
    } catch {
      // Preserve the connection or configuration failure.
    } finally {
      if (this.candidate === candidate) this.candidate = undefined;
    }
  }

  private delayFor(attempt: number): number {
    const exponential = this.reconnectPolicy.baseDelayMs * 2 ** Math.max(0, attempt - 1);
    const capped = Math.min(this.reconnectPolicy.maxDelayMs, exponential);
    const spread = this.reconnectPolicy.jitter;
    const jittered = capped * (1 - spread + Math.random() * spread * 2);
    return Math.min(MAX_TIMER_DELAY_MS, jittered);
  }

  private async waitBeforeRetry(failedAttempt: number): Promise<boolean> {
    const delay = this.delayFor(failedAttempt);
    try {
      await new Promise<void>((resolve, reject) => {
        const signal = this.recoveryAbort.signal;
        if (signal.aborted) {
          reject(signal.reason);
          return;
        }
        const timer = setTimeout(finishResolve, delay);
        const finish = () => {
          clearTimeout(timer);
          signal.removeEventListener("abort", finishReject);
        };
        function finishResolve() {
          finish();
          resolve();
        }
        function finishReject() {
          finish();
          reject(signal.reason);
        }
        signal.addEventListener("abort", finishReject, { once: true });
      });
      return this.state !== "closed";
    } catch {
      return false;
    }
  }

  private releaseReconnectTask(task: Promise<void>): void {
    if (this.reconnectTask === task) this.reconnectTask = undefined;
  }
}

function sanitizeEndpoint(requestUrl: string): string {
  try {
    return new URL(requestUrl).origin;
  } catch {
    return "unknown endpoint";
  }
}
