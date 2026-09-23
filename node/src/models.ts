/**
 * Core data models for the Photon client.
 *
 * These are plain data containers shared across every component (protocol, session, hooks). The
 * error taxonomy lives in `errors.ts`.
 */
export type HeaderValue = unknown;

/** Service-agnostic request metadata. */
export class RequestMetadata {
  readonly service: string;
  readonly operation: string;

  constructor(options: { service: string; operation: string }) {
    if (
      options === undefined ||
      options === null ||
      typeof options.service !== "string" ||
      typeof options.operation !== "string"
    ) {
      throw new TypeError("RequestMetadata requires service and operation");
    }
    this.service = options.service;
    this.operation = options.operation;
  }
}

/** An intercepted AWS SDK request to be forwarded to the cache. */
export class PhotonRequest {
  readonly method: string;
  readonly url: string;
  readonly headers: Record<string, HeaderValue>;
  readonly body: Buffer | string | null;
  readonly metadata: RequestMetadata;

  constructor(options: {
    method: string;
    url: string;
    headers?: Record<string, HeaderValue>;
    body?: Buffer | string | null;
    metadata: RequestMetadata;
  }) {
    if (
      options === undefined ||
      options === null ||
      typeof options.method !== "string" ||
      typeof options.url !== "string"
    ) {
      throw new TypeError("PhotonRequest requires string method and url");
    }
    if (
      options.metadata === undefined ||
      options.metadata === null ||
      typeof options.metadata.service !== "string" ||
      typeof options.metadata.operation !== "string"
    ) {
      throw new TypeError("PhotonRequest requires metadata");
    }
    this.method = options.method;
    this.url = options.url;
    this.headers = options.headers ?? {};
    this.body = options.body === undefined ? Buffer.alloc(0) : options.body;
    this.metadata = options.metadata;
  }
}

/** A response returned by the cache, to be adapted into an SDK response. */
export class PhotonResponse {
  readonly statusCode: number;
  readonly headers: Record<string, string>;
  readonly body: Buffer;

  constructor(options: { statusCode: number; headers?: Record<string, string>; body?: Buffer }) {
    this.statusCode = options.statusCode;
    this.headers = options.headers ?? {};
    this.body = options.body ?? Buffer.alloc(0);
  }
}

/** Resolved configuration returned by the `photon.config` handshake. */
export class ResolvedConfig {
  readonly version: unknown;
  /** Seconds between config refreshes. Validated as a positive integer at parse time. */
  readonly refreshInterval: number;
  readonly raw: Record<string, unknown> | undefined;

  constructor(options: {
    version?: unknown;
    refreshInterval: number;
    raw?: Record<string, unknown> | undefined;
  }) {
    this.version = options.version;
    this.refreshInterval = options.refreshInterval;
    this.raw = options.raw;
  }
}
