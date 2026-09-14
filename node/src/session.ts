/**
 * PhotonSession: connection bring-up, recovery, and the asynchronous request API.
 *
 * A session owns the active PhotonConnector, performs transport connect, AUTH,
 * and the mandatory photon.config handshake, and exposes request() for the AWS
 * SDK middleware to call. Dead connections are replaced through a single-flight
 * reconnect path with bounded backoff and circuit breaking.
 */
import { performance } from "node:perf_hooks";

import type { PhotonConnector } from "./connection.js";
import { PhotonCircuitOpenError, PhotonConnectionError, PhotonError } from "./errors.js";
import type { PhotonRequest, ResolvedConfig, PhotonResponse } from "./models.js";
import {
  parseConfigResponse,
  parseRequestResponse,
  serializeConfig,
  serializeRequest,
} from "./protocol.js";
import { PredicateEvaluator } from "./predicates.js";

const DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;
const DEFAULT_BASE_DELAY_MS = 100;
const DEFAULT_MAX_DELAY_MS = 5_000;
const DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 3;
const DEFAULT_CIRCUIT_OPEN_DURATION_MS = 30_000;

/**
 * Reconnect and circuit-breaker settings.
 *
 * A reconnect attempt creates a fresh connector and repeats AUTH and
 * photon.config. A value of zero disables reconnect attempts.
 */
export interface ReconnectPolicyOptions {
  /** Maximum connection attempts in one recovery cycle. Defaults to 3. */
  maxReconnectAttempts?: number;
  /** Initial full-jitter backoff cap in milliseconds. Defaults to 100. */
  baseDelayMs?: number;
  /** Maximum full-jitter backoff cap in milliseconds. Defaults to 5,000. */
  maxDelayMs?: number;
  /** Failed recovery cycles before opening the circuit. Defaults to 3. */
  circuitFailureThreshold?: number;
  /** Time the circuit remains open before one half-open probe. Defaults to 30,000. */
  circuitOpenDurationMs?: number;
}

interface ReconnectPolicy {
  maxReconnectAttempts: number;
  baseDelayMs: number;
  maxDelayMs: number;
  circuitFailureThreshold: number;
  circuitOpenDurationMs: number;
}

export type CircuitState = "closed" | "open" | "half-open";
type SessionState = "created" | "open" | "closed";
export type PhotonConnectorFactory = () => PhotonConnector;

function validateNonNegativeInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new PhotonError(`${name} must be a non-negative integer`);
  }
  return value;
}

function validatePositiveInteger(value: number, name: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new PhotonError(`${name} must be a positive integer`);
  }
  return value;
}

function resolveReconnectPolicy(
  options: ReconnectPolicyOptions | undefined,
  reconnectable: boolean,
): ReconnectPolicy {
  const policy = {
    maxReconnectAttempts:
      options?.maxReconnectAttempts ?? (reconnectable ? DEFAULT_MAX_RECONNECT_ATTEMPTS : 0),
    baseDelayMs: options?.baseDelayMs ?? DEFAULT_BASE_DELAY_MS,
    maxDelayMs: options?.maxDelayMs ?? DEFAULT_MAX_DELAY_MS,
    circuitFailureThreshold: options?.circuitFailureThreshold ?? DEFAULT_CIRCUIT_FAILURE_THRESHOLD,
    circuitOpenDurationMs: options?.circuitOpenDurationMs ?? DEFAULT_CIRCUIT_OPEN_DURATION_MS,
  };
  validateNonNegativeInteger(policy.maxReconnectAttempts, "maxReconnectAttempts");
  validatePositiveInteger(policy.baseDelayMs, "baseDelayMs");
  validatePositiveInteger(policy.maxDelayMs, "maxDelayMs");
  validatePositiveInteger(policy.circuitFailureThreshold, "circuitFailureThreshold");
  validatePositiveInteger(policy.circuitOpenDurationMs, "circuitOpenDurationMs");
  if (policy.maxDelayMs < policy.baseDelayMs) {
    throw new PhotonError("maxDelayMs must be at least baseDelayMs");
  }
  return policy;
}

/**
 * Parse a host:port endpoint.
 *
 * Endpoint parsing lives in the session layer so the transport carries no
 * parsing logic. Splitting on the final colon preserves IPv6-like hosts.
 */
export function parseEndpoint(endpoint: string): [string, number] {
  const separator = endpoint.lastIndexOf(":");
  if (separator < 1 || separator === endpoint.length - 1) {
    throw new PhotonError(`Invalid endpoint "${endpoint}": expected host:port`);
  }

  const endpointHost = endpoint.slice(0, separator);
  const host =
    endpointHost.startsWith("[") && endpointHost.endsWith("]")
      ? endpointHost.slice(1, -1)
      : endpointHost;
  const portText = endpoint.slice(separator + 1);
  if (host.length === 0) {
    throw new PhotonError(`Invalid endpoint "${endpoint}": host cannot be empty`);
  }
  if (!/^[+-]?\d+$/.test(portText.trim())) {
    throw new PhotonError(`Invalid port in endpoint "${endpoint}"`);
  }

  const port = Number(portText);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new PhotonError(`Port out of range in endpoint "${endpoint}": ${port}`);
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
  private readonly connectorFactory: PhotonConnectorFactory;
  private readonly reconnectPolicy: ReconnectPolicy;
  private connector: PhotonConnector | undefined;
  private recovery: Promise<PhotonConnector> | undefined;
  private consecutiveRecoveryFailures = 0;
  private circuit: CircuitState = "closed";
  private circuitOpenUntil = 0;
  private readonly recoveryAbort = new AbortController();

  constructor(
    connectorOrFactory: PhotonConnector | PhotonConnectorFactory,
    private readonly overrides: Readonly<Record<string, unknown>> = {},
    reconnectPolicy?: ReconnectPolicyOptions,
  ) {
    const reconnectable = typeof connectorOrFactory === "function";
    this.connectorFactory = reconnectable
      ? connectorOrFactory
      : singleConnectorFactory(connectorOrFactory);
    this.reconnectPolicy = resolveReconnectPolicy(reconnectPolicy, reconnectable);
  }

  get config(): ResolvedConfig | undefined {
    return this._config;
  }

  get isOpen(): boolean {
    return this.state === "open";
  }

  get circuitState(): CircuitState {
    return this.circuit;
  }

  /** Bring the session up: transport connect, AUTH, then the config handshake. */
  async open(): Promise<void> {
    if (this.state !== "created") {
      throw new PhotonError(`open() called on a ${this.state} session`);
    }

    try {
      const connector = this.connectorFactory();
      this.connector = connector;
      const initialized = await this.initialize(connector);
      if (this.state !== "created") {
        throw new PhotonError("session closed during open()");
      }
      this.publishConnection(connector, initialized);
      this.state = "open";
    } catch (error) {
      if (this.connector?.isReady) {
        try {
          await this.connector.close();
        } catch {
          // Preserve the error that caused session opening to fail.
        }
      }
      this.state = "closed";
      throw error;
    }
  }

  /**
   * Send matching requests to Photon and decline unmatched requests.
   *
   * A request already admitted to a failed connection is never replayed because
   * the server may have processed it. Its failure starts recovery in the
   * background so idempotent middleware fallback is not delayed.
   */
  async request(request: PhotonRequest): Promise<PhotonResponse | undefined> {
    if (this.state === "created") {
      throw new PhotonError("request() called on a created session");
    }

    let active = this.connector;
    const evaluator = this.predicateEvaluator;
    if (this.state === "open" && !evaluator.shouldRouteToPhoton(request)) {
      return undefined;
    }
    if (this.state === "open" && (active === undefined || active.isDead)) {
      active = await this.recover(active);
      if (
        this.predicateEvaluator !== evaluator &&
        !this.predicateEvaluator.shouldRouteToPhoton(request)
      ) {
        return undefined;
      }
    }
    if (active === undefined) {
      throw new PhotonConnectionError("Photon session has no active connection");
    }

    const [command, args] = serializeRequest(request);
    try {
      return parseRequestResponse(await active.execute(command, ...args));
    } catch (error) {
      if (this.state === "open" && active.isDead) {
        this.recoverInBackground(active);
      }
      throw error;
    }
  }

  /** Close active and recovering connectors. Idempotent. */
  async close(): Promise<void> {
    if (this.state === "closed") return;
    this.state = "closed";
    this.recoveryAbort.abort();
    const active = this.connector;
    const recovery = this.recovery;
    await active?.close();
    await recovery?.then(
      async (recovered) => {
        if (recovered !== active) await recovered.close();
      },
      () => undefined,
    );
  }

  /** Synchronous Node.js teardown used by AWS SDK client destroy(). */
  destroy(): void {
    if (this.state === "closed") return;
    this.state = "closed";
    this.recoveryAbort.abort();
    this.connector?.destroy();
  }

  private async initialize(
    connector: PhotonConnector,
  ): Promise<{ config: ResolvedConfig; evaluator: PredicateEvaluator }> {
    await connector.connect();
    try {
      await connector.authenticate();
      const [command, args] = serializeConfig(this.overrides);
      const config = parseConfigResponse(await connector.execute(command, ...args));
      return { config, evaluator: PredicateEvaluator.compile(config.raw) };
    } catch (error) {
      try {
        await connector.close();
      } catch {
        // Preserve the initialization error.
      }
      throw error;
    }
  }

  private publishConnection(
    connector: PhotonConnector,
    initialized: { config: ResolvedConfig; evaluator: PredicateEvaluator },
  ): void {
    this._config = initialized.config;
    this.predicateEvaluator = initialized.evaluator;
    // Publish the connector last so requests see its matching config.
    this.connector = connector;
  }

  private async recover(failedConnector: PhotonConnector | undefined): Promise<PhotonConnector> {
    if (this.state !== "open") {
      throw new PhotonConnectionError("Session closed during recovery");
    }
    if (
      this.connector !== undefined &&
      this.connector !== failedConnector &&
      this.connector.isReady
    ) {
      return this.connector;
    }
    if (this.recovery !== undefined) return this.recovery;

    let halfOpenAttempt = false;
    if (this.circuit === "open") {
      if (performance.now() < this.circuitOpenUntil) {
        throw new PhotonCircuitOpenError("Photon recovery circuit is open");
      }
      this.circuit = "half-open";
      halfOpenAttempt = true;
    }

    const recovery = this.performRecovery(failedConnector, halfOpenAttempt);
    this.recovery = recovery;
    void recovery.then(
      () => this.releaseRecovery(recovery),
      () => this.releaseRecovery(recovery),
    );
    return recovery;
  }

  private recoverInBackground(failedConnector: PhotonConnector): void {
    void this.recover(failedConnector).catch(() => {
      // Circuit state records the failure for subsequent requests.
    });
  }

  private async performRecovery(
    failedConnector: PhotonConnector | undefined,
    halfOpenAttempt: boolean,
  ): Promise<PhotonConnector> {
    await failedConnector?.close();
    let lastFailure: unknown = new PhotonConnectionError("Photon reconnect attempts exhausted");

    for (let attempt = 1; attempt <= this.reconnectPolicy.maxReconnectAttempts; attempt++) {
      if (this.state !== "open") {
        lastFailure = new PhotonConnectionError("Session closed during recovery");
        break;
      }

      let candidate: PhotonConnector | undefined;
      try {
        candidate = this.connectorFactory();
        const initialized = await this.initialize(candidate);
        if (this.state !== "open") {
          await candidate.close();
          throw new PhotonConnectionError("Session closed during recovery");
        }
        this.publishConnection(candidate, initialized);
        this.consecutiveRecoveryFailures = 0;
        this.circuit = "closed";
        this.circuitOpenUntil = 0;
        return candidate;
      } catch (error) {
        lastFailure = error;
        try {
          await candidate?.close();
        } catch {
          // Preserve the reconnect failure.
        }
        if (
          attempt < this.reconnectPolicy.maxReconnectAttempts &&
          !(await this.waitBeforeRetry(attempt))
        ) {
          lastFailure = new PhotonConnectionError("Session closed during recovery");
          break;
        }
      }
    }

    this.consecutiveRecoveryFailures++;
    if (
      halfOpenAttempt ||
      this.consecutiveRecoveryFailures >= this.reconnectPolicy.circuitFailureThreshold
    ) {
      this.circuit = "open";
      this.circuitOpenUntil = performance.now() + this.reconnectPolicy.circuitOpenDurationMs;
    } else {
      this.circuit = "closed";
    }
    if (lastFailure instanceof PhotonError) throw lastFailure;
    throw new PhotonConnectionError("Photon reconnect failed", { cause: lastFailure });
  }

  private async waitBeforeRetry(failedAttempt: number): Promise<boolean> {
    const exponentialCap = this.reconnectPolicy.baseDelayMs * 2 ** Math.max(0, failedAttempt - 1);
    const cap = Math.min(this.reconnectPolicy.maxDelayMs, exponentialCap);
    const delay = Math.floor(Math.random() * (cap + 1));
    if (delay === 0) return this.state === "open";

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
      return this.state === "open";
    } catch {
      return false;
    }
  }

  private releaseRecovery(recovery: Promise<PhotonConnector>): void {
    if (this.recovery === recovery) this.recovery = undefined;
  }
}
