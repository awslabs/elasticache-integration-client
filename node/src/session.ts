/**
 * PhotonSession: connection bring-up and the asynchronous request API.
 *
 * A session owns one PhotonConnector, performs transport connect, AUTH, and
 * the mandatory photon.config handshake, and exposes request() for the AWS SDK
 * middleware to call.
 *
 * The resolved config is fetched once and its CEL predicates determine which
 * requests are sent to Photon. The middleware may pass predicate declines and
 * idempotent Photon failures through to the origin.
 */
import type { PhotonConnector } from "./connection.js";
import { PhotonError } from "./errors.js";
import type { PhotonRequest, ResolvedConfig, PhotonResponse } from "./models.js";
import {
  parseConfigResponse,
  parseRequestResponse,
  serializeConfig,
  serializeRequest,
} from "./protocol.js";
import { PredicateEvaluator } from "./predicates.js";

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

type SessionState = "created" | "open" | "closed";

/** Owns a cache connection and exposes the asynchronous request API. */
export class PhotonSession {
  private config: ResolvedConfig | undefined;
  private predicateEvaluator = PredicateEvaluator.compile(undefined);
  private state: SessionState = "created";

  constructor(
    private readonly connector: PhotonConnector,
    private readonly overrides: Readonly<Record<string, unknown>> = {},
  ) {}

  get resolvedConfig(): ResolvedConfig | undefined {
    return this.config;
  }

  get isOpen(): boolean {
    return this.state === "open";
  }

  /** Bring the session up: transport connect, AUTH, then the config handshake. */
  async open(): Promise<void> {
    if (this.state !== "created") {
      throw new PhotonError(`open() called on a ${this.state} session`);
    }

    await this.connector.connect();
    try {
      await this.connector.authenticate();
      const [command, args] = serializeConfig(this.overrides);
      const config = parseConfigResponse(await this.connector.execute(command, ...args));
      if (this.state !== "created") {
        throw new PhotonError("session closed during open()");
      }
      this.config = config;
      this.predicateEvaluator = PredicateEvaluator.compile(config.raw);
      this.state = "open";
    } catch (error) {
      try {
        await this.close();
      } catch {
        // Preserve the error that caused session opening to fail.
      }
      throw error;
    }
  }

  /**
   * Send matching requests to Photon and decline unmatched requests.
   *
   * An undefined result tells the middleware to send the original signed
   * request to the origin. Connector and protocol errors propagate.
   */
  async request(request: PhotonRequest): Promise<PhotonResponse | undefined> {
    if (this.state === "created") {
      throw new PhotonError("request() called on a created session");
    }
    if (this.state === "open" && !this.predicateEvaluator.route(request)) {
      return undefined;
    }
    const [command, args] = serializeRequest(request);
    return parseRequestResponse(await this.connector.execute(command, ...args));
  }

  /** Close the connector and mark the session CLOSED. Idempotent. */
  async close(): Promise<void> {
    if (this.state === "closed") return;
    this.state = "closed";
    await this.connector.close();
  }

  /** Synchronous Node.js teardown used by AWS SDK client destroy(). */
  destroy(): void {
    if (this.state === "closed") return;
    this.state = "closed";
    this.connector.destroy();
  }
}
