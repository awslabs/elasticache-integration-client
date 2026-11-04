/**
 * ElastiCacheIntegrationsSession owns cache connection recovery and the asynchronous request API.
 *
 * The initial connection retries until the connection timeout passes, so client
 * creation fails loudly. After a live connection is lost, reconnect attempts
 * continue in the background until one succeeds or the session closes. A
 * request that has not been sent waits up to the connection timeout for the
 * reconnect, then may use the origin fallback window while no connector is
 * available.
 *
 * The resolved config and its CEL predicates determine which requests are sent
 * to ElastiCacheIntegrations. The middleware may pass predicate declines, disconnected requests,
 * and dropped-connection failures for idempotent operations through to origin.
 *
 * Each active connection owns background refreshers for resolved config and
 * authentication. Config refresh failures keep the last known-good state.
 * Once the staleness window passes with no config confirmed by the server, the
 * config is no longer trusted: the connection is dropped so a new one
 * re-resolves it, and requests fall back meanwhile.
 */
import { performance } from "node:perf_hooks";

import { DEFAULT_CONNECTION_TIMEOUT } from "./connection.js";
import type { ElastiCacheIntegrationsConnector } from "./connection.js";
import {
  ElastiCacheIntegrationsCacheUnavailable,
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsConfigUnevaluable,
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsTimeoutError,
} from "./errors.js";
import type {
  ElastiCacheIntegrationsRequest,
  ResolvedConfig,
  ElastiCacheIntegrationsResponse,
} from "./models.js";
import {
  parseConfigResponse,
  parseRequestResponse,
  serializeConfig,
  serializeRequest,
} from "./protocol.js";
import { PredicateEvaluator } from "./predicates.js";
import { Refresher } from "./refresh.js";

const AUTH_ERROR = "AUTH_ERROR";
/** Cap on each background reconnect attempt. */
const RECONNECT_ATTEMPT_TIMEOUT_MS = 5_000;
const DEFAULT_AUTH_REFRESH_INTERVAL_MS = 900_000;
const DEFAULT_BASE_DELAY_MS = 100;
const DEFAULT_MAX_DELAY_MS = 3_200;
const DEFAULT_JITTER = 0.2;
const MAX_TIMER_DELAY_MS = 2_147_483_647;

/** Reconnect and origin-fallback settings. */
export interface ReconnectPolicyOptions {
  /**
   * Origin fallback window for each outage, in milliseconds, counted from the end of the
   * reconnect wait.
   *
   * `null`, `undefined`, or a negative value means indefinitely. Zero disables
   * fallback, and a positive value allows fallback until that deadline.
   */
  fallbackDurationMs?: number | null;
  /**
   * How long `open()` keeps trying to connect, and how long a request waits for a reconnect
   * after a live connection is lost, in milliseconds. Defaults to 2,000.
   */
  connectionTimeoutMs?: number;
  /** How often a live connection obtains fresh credentials and runs AUTH again. */
  authRefreshIntervalMs?: number;
  /** Initial reconnect delay in milliseconds. Defaults to 100. */
  baseDelayMs?: number;
  /** Maximum reconnect delay before jitter. Defaults to 3,200. */
  maxDelayMs?: number;
  /** Symmetric jitter ratio for reconnect and background refresh delays. Defaults to 0.2. */
  jitter?: number;
}

/** Default staleness window: one hour. */
export const DEFAULT_STALENESS_DURATION_MS = 3_600_000;

/** A config failure that cannot fix itself, so it is logged at error level on every occurrence. */
function isPermanentConfigFailure(error: ElastiCacheIntegrationsError): boolean {
  return (
    error instanceof ElastiCacheIntegrationsOverridesRejected ||
    error instanceof ElastiCacheIntegrationsConfigUnevaluable
  );
}

/** The cause of a permanent config failure, worded the same on every path. */
function configFailureCause(error: ElastiCacheIntegrationsError): string {
  return error instanceof ElastiCacheIntegrationsOverridesRejected
    ? "the proxy rejected the overrides (CONFIG_INVALID)"
    : "the config has a predicate this client cannot evaluate";
}

/** Session recovery settings. `clock` is injectable for deterministic staleness tests. */
export interface ElastiCacheIntegrationsSessionOptions extends ReconnectPolicyOptions {
  /** How long the resolved config stays trusted while refreshes are failing, in milliseconds. */
  stalenessDurationMs?: number;
  /** Monotonic clock in milliseconds. */
  clock?: () => number;
}

interface ReconnectPolicy {
  fallbackWindowMs: number;
  connectionTimeoutMs: number;
  authRefreshIntervalMs: number;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: number;
}

export type ConnectionState = "init" | "alive" | "reconnecting" | "closed";
type SessionState = "created" | "open" | "closed";
export interface ElastiCacheIntegrationsConnectorFactory {
  (): ElastiCacheIntegrationsConnector;
  /** Release resources shared by every connector produced by this factory. */
  close?: () => void;
}

function timerDelay(value: number, name: string): number {
  if (!Number.isFinite(value) || value <= 0 || value > MAX_TIMER_DELAY_MS) {
    throw new ElastiCacheIntegrationsConfigError(
      `${name} must be greater than zero and at most ${MAX_TIMER_DELAY_MS}`,
    );
  }
  return value;
}

function jitteredTimerInterval(value: number, name: string, jitter: number): number {
  const interval = timerDelay(value, name);
  if (interval * (1 + jitter) > MAX_TIMER_DELAY_MS) {
    throw new ElastiCacheIntegrationsConfigError(
      `${name} must remain at most ${MAX_TIMER_DELAY_MS} after jitter is applied`,
    );
  }
  return interval;
}

function fallbackWindow(value: number | null | undefined): number {
  if (value === null || value === undefined) return Number.POSITIVE_INFINITY;
  if (!Number.isSafeInteger(value)) {
    throw new ElastiCacheIntegrationsConfigError(
      "fallbackDurationMs must be a whole number of milliseconds, null, or undefined",
    );
  }
  return value < 0 ? Number.POSITIVE_INFINITY : value;
}

function resolveReconnectPolicy(options: ReconnectPolicyOptions | undefined): ReconnectPolicy {
  const baseDelayMs = timerDelay(options?.baseDelayMs ?? DEFAULT_BASE_DELAY_MS, "baseDelayMs");
  const maxDelayMs = timerDelay(options?.maxDelayMs ?? DEFAULT_MAX_DELAY_MS, "maxDelayMs");
  if (maxDelayMs < baseDelayMs) {
    throw new ElastiCacheIntegrationsConfigError("maxDelayMs must be at least baseDelayMs");
  }
  const jitter = options?.jitter ?? DEFAULT_JITTER;
  if (!Number.isFinite(jitter) || jitter < 0 || jitter >= 1) {
    throw new ElastiCacheIntegrationsConfigError("jitter must be in [0, 1)");
  }
  return {
    fallbackWindowMs: fallbackWindow(options?.fallbackDurationMs),
    connectionTimeoutMs: timerDelay(
      options?.connectionTimeoutMs ?? DEFAULT_CONNECTION_TIMEOUT,
      "connectionTimeoutMs",
    ),
    authRefreshIntervalMs: jitteredTimerInterval(
      options?.authRefreshIntervalMs ?? DEFAULT_AUTH_REFRESH_INTERVAL_MS,
      "authRefreshIntervalMs",
      jitter,
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
    throw new ElastiCacheIntegrationsConfigError(
      `Invalid endpoint "${endpoint}": expected host:port`,
    );
  }

  const endpointHost = endpoint.slice(0, separator);
  const host =
    endpointHost.startsWith("[") && endpointHost.endsWith("]")
      ? endpointHost.slice(1, -1)
      : endpointHost;
  const portText = endpoint.slice(separator + 1);
  if (host.length === 0) {
    throw new ElastiCacheIntegrationsConfigError(
      `Invalid endpoint "${endpoint}": host cannot be empty`,
    );
  }
  if (!/^[+-]?\d+$/.test(portText.trim())) {
    throw new ElastiCacheIntegrationsConfigError(`Invalid port in endpoint "${endpoint}"`);
  }

  const port = Number(portText);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new ElastiCacheIntegrationsConfigError(
      `Port out of range in endpoint "${endpoint}": ${port}`,
    );
  }
  return [host, port];
}

function singleConnectorFactory(
  connector: ElastiCacheIntegrationsConnector,
): ElastiCacheIntegrationsConnectorFactory {
  let supplied = false;
  return () => {
    if (supplied) {
      throw new ElastiCacheIntegrationsConnectionError(
        "This session does not have a reconnectable connector factory",
      );
    }
    supplied = true;
    return connector;
  };
}

/** Owns the active cache connection and exposes the asynchronous request API. */
export class ElastiCacheIntegrationsSession {
  private _config: ResolvedConfig | undefined;
  private predicateEvaluator = PredicateEvaluator.compile(undefined);
  private configRefresher: Refresher | undefined;
  private reauthRefresher: Refresher | undefined;
  private state: SessionState = "created";
  private connection: ConnectionState = "init";
  private readonly connectorFactory: ElastiCacheIntegrationsConnectorFactory;
  private readonly reconnectPolicy: ReconnectPolicy;
  private readonly reconnectable: boolean;
  private connector: ElastiCacheIntegrationsConnector | undefined;
  private candidate: ElastiCacheIntegrationsConnector | undefined;
  private reconnectTask: Promise<void> | undefined;
  /** When requests stop waiting for a reconnect during the current outage. */
  private readyDeadline = 0;
  /** When origin fallback ends during the current outage. */
  private fallbackDeadline = 0;
  /** Resolvers of requests waiting for a reconnect, woken on reconnect and on close. */
  private readonly connectorWaiters = new Set<() => void>();
  private readonly disconnected = new WeakSet<ElastiCacheIntegrationsConnector>();
  private readonly recoveryAbort = new AbortController();
  private factoryClosed = false;
  /** Whether `overrides` have already been sent, so a refresh can use the no-argument form. */
  private overridesSent = false;
  private readonly stalenessDurationMs: number;
  private readonly clock: () => number;
  /** When the config stops being trusted; restarted by every confirmation from the server. */
  private configDeadline = 0;

  constructor(
    connectorOrFactory: ElastiCacheIntegrationsConnector | ElastiCacheIntegrationsConnectorFactory,
    private readonly overrides: Readonly<Record<string, unknown>> = {},
    sessionOptions?: ElastiCacheIntegrationsSessionOptions,
  ) {
    this.reconnectable = typeof connectorOrFactory === "function";
    this.connectorFactory = this.reconnectable
      ? (connectorOrFactory as ElastiCacheIntegrationsConnectorFactory)
      : singleConnectorFactory(connectorOrFactory as ElastiCacheIntegrationsConnector);
    this.reconnectPolicy = resolveReconnectPolicy(sessionOptions);
    this.stalenessDurationMs = sessionOptions?.stalenessDurationMs ?? DEFAULT_STALENESS_DURATION_MS;
    this.clock = sessionOptions?.clock ?? (() => performance.now());
  }

  get config(): ResolvedConfig | undefined {
    return this._config;
  }

  get isOpen(): boolean {
    return this.state === "open";
  }

  get isConnectionHealthy(): boolean {
    return (
      this.state === "open" &&
      this.connection === "alive" &&
      this.connector !== undefined &&
      this.connector.isReady
    );
  }

  get connectionState(): ConnectionState {
    return this.connection;
  }

  /** Whether the config has gone unconfirmed long enough to stop trusting it. */
  get configStale(): boolean {
    return this.clock() >= this.configDeadline;
  }

  /**
   * Open the initial connection, retrying until the connection timeout passes.
   *
   * A session built from a single connector makes one attempt.
   */
  async open(): Promise<void> {
    if (this.state !== "created") {
      throw new ElastiCacheIntegrationsError(`open() called on a ${this.state} session`);
    }

    const timeoutMs = this.reconnectPolicy.connectionTimeoutMs;
    const timeoutMessage = `opening a connection timed out after ${timeoutMs / 1_000}s`;
    const deadline = performance.now() + timeoutMs;
    let lastFailure: unknown;
    let attempt = 0;
    while (true) {
      attempt++;
      if (this.state !== "created") {
        throw new ElastiCacheIntegrationsConnectionError("Session closed during open()");
      }

      let candidate: ElastiCacheIntegrationsConnector | undefined;
      try {
        candidate = this.createCandidate();
        const initialized = await this.initializeWithTimeout(
          candidate,
          Math.max(1, deadline - performance.now()),
          timeoutMessage,
        );
        if (this.state !== "created") {
          throw new ElastiCacheIntegrationsConnectionError("Session closed during open()");
        }
        this.publishConnection(candidate, initialized);
        this.candidate = undefined;
        this.state = "open";
        this.connection = "alive";
        return;
      } catch (error) {
        lastFailure = error;
        await this.closeCandidate(candidate);
      }

      const remaining = deadline - performance.now();
      if (!this.reconnectable || remaining <= 0) break;
      if (!(await this.waitBeforeRetry(attempt, remaining))) {
        this.state = "closed";
        this.connection = "closed";
        throw new ElastiCacheIntegrationsConnectionError("Session closed during open()");
      }
      if (deadline - performance.now() <= 0) break;
    }

    this.state = "closed";
    this.connection = "closed";
    if (this.reconnectable && lastFailure instanceof ElastiCacheIntegrationsConnectionError) {
      throw new ElastiCacheIntegrationsConnectionError(timeoutMessage, { cause: lastFailure });
    }
    if (lastFailure instanceof Error) throw lastFailure;
    throw new ElastiCacheIntegrationsConnectionError(timeoutMessage, { cause: lastFailure });
  }

  /**
   * Send one `aws.config` refresh and apply the result.
   *
   * A null reply installs nothing; any other reply replaces the config, predicates, and interval.
   * Either way the server confirmed the config, restarting the staleness window. Nothing is sent
   * while no connection is available.
   *
   * A failed refresh keeps the current config until the staleness window passes, then drops the
   * connection so a new one re-resolves it. A successful refresh never drops. Transport failures
   * start recovery immediately.
   */
  private async refreshOnce(): Promise<void> {
    const connector = this.connector;
    const refresher = this.configRefresher;
    if (
      this.state !== "open" ||
      this.connection !== "alive" ||
      connector === undefined ||
      refresher === undefined
    ) {
      return;
    }
    const [command, args] = serializeConfig(this.overridesSent ? null : this.overrides);
    let value;
    try {
      value = await connector.execute(command, ...args);
    } catch (error) {
      if (!(error instanceof ElastiCacheIntegrationsError)) throw error;
      if (
        error instanceof ElastiCacheIntegrationsConnectionLost ||
        error instanceof ElastiCacheIntegrationsProtocolError ||
        error instanceof ElastiCacheIntegrationsTimeoutError
      ) {
        if (this.state === "open" && this.connector === connector) {
          this.handleDisconnect(connector);
        }
        return;
      }
      this.refreshFailed(error, connector, refresher);
      return;
    }

    // Ignore a reply from a connector that was replaced while refresh was in flight.
    if (
      this.state !== "open" ||
      this.connection !== "alive" ||
      this.connector !== connector ||
      this.configRefresher !== refresher
    ) {
      return;
    }
    if (value === null) {
      this.configConfirmed();
      return;
    }

    try {
      const config = parseConfigResponse(value);
      const predicateEvaluator = PredicateEvaluator.compile(config.raw);
      // Reschedule before the swap: an interval this refresher cannot schedule is a malformed
      // config, and rejecting it first keeps the whole previous config rather than half of it.
      refresher.setInterval(config.refreshInterval);
      this.overridesSent = true;
      this._config = config;
      this.predicateEvaluator = predicateEvaluator;
      this.configConfirmed();
    } catch (error) {
      if (!(error instanceof ElastiCacheIntegrationsError)) throw error;
      this.refreshFailed(error, connector, refresher);
    }
  }

  /** Restart the staleness window after any confirmation from the server. */
  private configConfirmed(): void {
    this.configDeadline = this.clock() + this.stalenessDurationMs;
  }

  /**
   * Log a failed refresh, and drop `connector` once the staleness window has passed.
   *
   * Does nothing if the connector was replaced while the refresh was in flight.
   */
  private refreshFailed(
    error: ElastiCacheIntegrationsError,
    connector: ElastiCacheIntegrationsConnector,
    refresher: Refresher,
  ): void {
    if (
      this.state !== "open" ||
      this.connection !== "alive" ||
      this.connector !== connector ||
      this.configRefresher !== refresher
    ) {
      return;
    }
    if (isPermanentConfigFailure(error)) {
      console.error(
        `Config refresh failed: ${configFailureCause(error)}. Keeping the last good config ` +
          `until the staleness window drops the connection: ${error.message}`,
      );
    } else {
      console.warn(`Config refresh failed; keeping the current config: ${error.message}`);
    }
    if (this.configStale) {
      console.warn(
        `No config confirmed for ${this.stalenessDurationMs / 1_000}s; ` +
          "dropping the connection so a new one resolves it",
      );
      this.handleDisconnect(connector);
    }
  }

  /**
   * Send matching requests to ElastiCacheIntegrations and decline requests that are safe to send
   * to origin.
   *
   * A disconnected request has not been sent, so it waits for the reconnect until the outage's
   * `readyDeadline`, then may fall back while the per-outage window is open. Once execute
   * starts, every failure propagates:
   * the request may have reached ElastiCacheIntegrations and must not be directly replayed.
   */
  async request(
    request: ElastiCacheIntegrationsRequest,
  ): Promise<ElastiCacheIntegrationsResponse | undefined> {
    if (this.state === "created") {
      throw new ElastiCacheIntegrationsError("request() called on a created session");
    }
    if (this.state === "open" && !this.predicateEvaluator.shouldRouteToCache(request)) {
      return undefined;
    }

    let currentConnector = this.connector;
    if (this.state === "open" && currentConnector?.isDead) {
      this.handleDisconnect(currentConnector);
      currentConnector = undefined;
    }
    if (this.state === "open" && currentConnector === undefined) {
      currentConnector = await this.waitForConnector();
      // A close during the wait also ends it here, and the request falls back
      if (currentConnector === undefined) {
        if (this.fallbackAllowed()) return undefined;
        throw new ElastiCacheIntegrationsCacheUnavailable(sanitizeEndpoint(request.url));
      }
    }
    if (currentConnector === undefined) {
      throw new ElastiCacheIntegrationsConnectionError(
        "ElastiCacheIntegrations session has no active connection",
      );
    }

    const [command, args] = serializeRequest(request);
    try {
      return parseRequestResponse(await currentConnector.execute(command, ...args));
    } catch (error) {
      if (error instanceof ElastiCacheIntegrationsRejectedError && error.code === AUTH_ERROR) {
        this.handleDisconnect(currentConnector);
        return undefined;
      }
      throw error;
    }
  }

  /**
   * Wait until a reconnect is published or the outage's `readyDeadline` passes. Returns the
   * published connector, or undefined if none arrived or the session closed.
   */
  private async waitForConnector(): Promise<ElastiCacheIntegrationsConnector | undefined> {
    while (this.state === "open" && this.connector === undefined) {
      const remaining = this.readyDeadline - performance.now();
      if (remaining <= 0) break;
      await new Promise<void>((resolve) => {
        const wake = () => {
          clearTimeout(timer);
          this.connectorWaiters.delete(wake);
          resolve();
        };
        const timer = setTimeout(wake, remaining);
        this.connectorWaiters.add(wake);
      });
    }
    return this.state === "open" ? this.connector : undefined;
  }

  private wakeWaiters(): void {
    for (const wake of [...this.connectorWaiters]) wake();
  }

  /** Whether a request with no connector may go to origin. Strict mode (0) never allows it. */
  private fallbackAllowed(): boolean {
    return this.reconnectPolicy.fallbackWindowMs !== 0 && performance.now() < this.fallbackDeadline;
  }

  /** Stop background refresh and close active and recovering connectors. Idempotent. */
  async close(): Promise<void> {
    if (this.state === "closed") {
      this.closeFactory();
      return;
    }
    this.state = "closed";
    this.connection = "closed";
    this.reauthRefresher?.stop();
    this.configRefresher?.stop();
    this.recoveryAbort.abort();
    this.wakeWaiters();
    this.closeFactory();
    const active = this.connector;
    const candidate = this.candidate;
    const reconnectTask = this.reconnectTask;
    candidate?.destroy();
    await Promise.all([active?.close(), candidate?.close(), reconnectTask?.catch(() => undefined)]);
  }

  /** Synchronous Node.js teardown used by AWS SDK client destroy(). */
  destroy(): void {
    if (this.state === "closed") {
      this.closeFactory();
      return;
    }
    this.state = "closed";
    this.connection = "closed";
    this.reauthRefresher?.stop();
    this.configRefresher?.stop();
    this.recoveryAbort.abort();
    this.wakeWaiters();
    this.closeFactory();
    this.candidate?.destroy();
    this.connector?.destroy();
  }

  private closeFactory(): void {
    if (this.factoryClosed) return;
    this.factoryClosed = true;
    this.connectorFactory.close?.();
  }

  private createCandidate(): ElastiCacheIntegrationsConnector {
    const candidate = this.connectorFactory();
    candidate.onDisconnect = () => this.handleDisconnect(candidate);
    this.candidate = candidate;
    return candidate;
  }

  private async initialize(
    connector: ElastiCacheIntegrationsConnector,
  ): Promise<{ config: ResolvedConfig; evaluator: PredicateEvaluator }> {
    await connector.connect();
    await connector.handshake();
    const [command, args] = serializeConfig(this.overrides);
    const reply = await connector.execute(command, ...args);
    try {
      const config = parseConfigResponse(reply);
      return { config, evaluator: PredicateEvaluator.compile(config.raw) };
    } catch (error) {
      // Logged on every attempt: the reconnect loop discards its failures, and these never
      // fix themselves.
      if (error instanceof ElastiCacheIntegrationsError && isPermanentConfigFailure(error)) {
        console.error(
          `Configuring a connection failed: ${configFailureCause(error)}. ` +
            `The connection is closed and retried: ${error.message}`,
        );
      }
      throw error;
    }
  }

  private async initializeWithTimeout(
    connector: ElastiCacheIntegrationsConnector,
    timeout: number,
    timeoutMessage: string,
  ): Promise<{ config: ResolvedConfig; evaluator: PredicateEvaluator }> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        this.initialize(connector),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            connector.destroy();
            reject(new ElastiCacheIntegrationsConnectionError(timeoutMessage));
          }, timeout);
        }),
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  }

  private publishConnection(
    connector: ElastiCacheIntegrationsConnector,
    initialized: { config: ResolvedConfig; evaluator: PredicateEvaluator },
  ): void {
    if (this.disconnected.has(connector) || connector.isDead) {
      throw new ElastiCacheIntegrationsConnectionError("Connection closed during initialization");
    }
    connector.markReady();
    // Refreshers belong to the published connection. A replacement has just authenticated
    // through HELLO, so publishing it intentionally resets both schedules and failure counts.
    const configRefresher = new Refresher(
      initialized.config.refreshInterval,
      () => this.refreshOnce(),
      { jitter: this.reconnectPolicy.jitter, name: "aws-elasticache-integration-client-refresher" },
    );
    const reauthRefresher = new Refresher(
      this.reconnectPolicy.authRefreshIntervalMs / 1_000,
      () => this.reauthenticateOnce(),
      {
        jitter: this.reconnectPolicy.jitter,
        name: "aws-elasticache-integration-client-reauth",
        failureDelayMs: (failures) => this.delayFor(failures),
      },
    );
    this.reauthRefresher?.stop();
    this.configRefresher?.stop();
    this._config = initialized.config;
    this.predicateEvaluator = initialized.evaluator;
    this.connector = connector;
    this.overridesSent = true;
    this.configConfirmed();
    this.configRefresher = configRefresher;
    this.reauthRefresher = reauthRefresher;
    configRefresher.start();
    reauthRefresher.start();
  }

  /** Obtain fresh credentials and run plain AUTH on the live connector. */
  private async reauthenticateOnce(): Promise<void> {
    const connector = this.connector;
    if (
      this.state !== "open" ||
      this.connection !== "alive" ||
      connector === undefined ||
      this.reauthRefresher === undefined
    ) {
      return;
    }
    await connector.reauthenticate();
  }

  private handleDisconnect(connector: ElastiCacheIntegrationsConnector): void {
    this.disconnected.add(connector);
    if (this.state !== "open" || this.connector !== connector) return;

    // Set both deadlines from one clock reading before clearing the connector, so a request
    // that observes no connector always sees the current outage's deadlines.
    this.readyDeadline = performance.now() + this.reconnectPolicy.connectionTimeoutMs;
    this.fallbackDeadline = this.readyDeadline + this.reconnectPolicy.fallbackWindowMs;
    const configRefresher = this.configRefresher;
    const reauthRefresher = this.reauthRefresher;
    this.configRefresher = undefined;
    this.reauthRefresher = undefined;
    reauthRefresher?.stop();
    configRefresher?.stop();
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
      let candidate: ElastiCacheIntegrationsConnector | undefined;
      try {
        candidate = this.createCandidate();
        const initialized = await this.initializeWithTimeout(
          candidate,
          RECONNECT_ATTEMPT_TIMEOUT_MS,
          `opening a connection timed out after ${RECONNECT_ATTEMPT_TIMEOUT_MS / 1_000}s`,
        );
        if (this.state !== "open" || this.connection !== "reconnecting") {
          await this.closeCandidate(candidate);
          return;
        }
        this.publishConnection(candidate, initialized);
        this.candidate = undefined;
        this.connection = "alive";
        this.readyDeadline = 0;
        this.fallbackDeadline = 0;
        this.wakeWaiters();
        return;
      } catch {
        await this.closeCandidate(candidate);
        if (!(await this.waitBeforeRetry(attempt))) return;
      }
    }
  }

  private async closeCandidate(
    candidate: ElastiCacheIntegrationsConnector | undefined,
  ): Promise<void> {
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

  /** Sleep before the next attempt, at most `maxDelayMs`. False if the session closed. */
  private async waitBeforeRetry(
    failedAttempt: number,
    maxDelayMs = Number.POSITIVE_INFINITY,
  ): Promise<boolean> {
    const delay = Math.min(this.delayFor(failedAttempt), maxDelayMs);
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
