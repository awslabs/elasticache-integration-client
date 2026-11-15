/**
 * AWS SDK v3 interception for the ElastiCacheIntegrations client.
 *
 * The middleware receives the final serialized, signed HttpRequest, strips the
 * AWS SigV4 authentication headers and the JS SDK-only headers, and builds a ElastiCacheIntegrationsRequest.
 * A wrapper around the client's request handler, which is the only step that gets the call's
 * `abortSignal`, then calls ElastiCacheIntegrationsSession and returns a synthetic HttpResponse. Predicate declines, explicit ElastiCacheIntegrations
 * rejections, and indeterminate failures for idempotent operations return to
 * the SDK's origin pipeline while preserving its normal retry and
 * deserialization behavior.
 *
 * The SDK's real signer is left intact so requests can still be forwarded to
 * the origin with valid signatures; only the ElastiCacheIntegrations path drops the auth
 * headers.
 */
import { formatUrl } from "@aws-sdk/core/util";
import { HttpRequest, HttpResponse } from "@smithy/core/protocols";
import type {
  AwsCredentialIdentityProvider,
  DeserializeMiddleware,
  HandlerExecutionContext,
  HttpHandlerOptions,
  MiddlewareStack,
} from "@smithy/types";
import {
  ElastiCacheIntegrationsConnectionError,
  ElastiCacheIntegrationsConnectionLost,
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsRejectedError,
  ElastiCacheIntegrationsRequestNotSent,
  ElastiCacheIntegrationsTimeoutError,
} from "./errors.js";
import { isIdempotentOperation } from "./idempotency.js";
import { ElastiCacheIntegrationsRequest, RequestMetadata } from "./models.js";
import type { ElastiCacheIntegrationsResponse } from "./models.js";
import type { ElastiCacheIntegrationsSession } from "./session.js";

const CACHE_MIDDLEWARE = "elastiCacheIntegrations";
const ORIGIN_ERROR = "ORIGIN_ERROR";

type RetryableError = Error & { $retryable: Record<string, never> };

function markRetryable(error: Error): RetryableError {
  return Object.assign(error, { $retryable: {} });
}

function isTransportFailure(error: ElastiCacheIntegrationsError): boolean {
  return (
    error instanceof ElastiCacheIntegrationsTimeoutError ||
    error instanceof ElastiCacheIntegrationsConnectionLost ||
    error instanceof ElastiCacheIntegrationsConnectionError
  );
}

/** The part of an AWS SDK v3 client used by ElastiCacheIntegrationsHandle. */
export interface ElastiCacheIntegrationsCompatibleClient {
  config: { credentials: AwsCredentialIdentityProvider; requestHandler: object };
  middlewareStack: MiddlewareStack<any, any>;
  destroy(): void;
}

/**
 * Whether `body` fits in one `aws.request` frame. A missing body does. Any
 * body that is not bytes-like, such as a stream, does not: reading it to
 * serialize would consume it, leaving nothing for the origin send.
 */
function isCarriableBody(body: unknown): body is string | Uint8Array | null | undefined {
  return body == null || typeof body === "string" || body instanceof Uint8Array;
}

/** Normalize a carriable prepared body to bytes. A missing body is an empty body. */
function normalizeBody(body: string | Uint8Array | null | undefined): Buffer {
  if (body == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(body)) return body;
  if (typeof body === "string") return Buffer.from(body, "utf8");
  // Buffer.from(object) calls valueOf() first, which the SDK's JSON body subclass turns into a
  // warning and a UTF-8 round trip. copyBytesFrom reads the bytes directly.
  return Buffer.copyBytesFrom(body);
}

/**
 * Headers removed before forwarding to ElastiCacheIntegrations: the SigV4 authentication headers,
 * plus headers the JS SDK adds that `shared/sdk-request-cases.json` does not expect. The service
 * routes on the frame's URL, so it does not need `host`.
 */
const STRIPPED_HEADERS = new Set([
  "authorization",
  "x-amz-date",
  "x-amz-security-token",
  "host",
  "x-amz-content-sha256",
  "x-amz-user-agent",
]);

/** Copy request headers without the SigV4 and JS SDK-only headers. */
function stripSigningHeaders(headers: Record<string, string>): Record<string, string> {
  const stripped: Record<string, string> = {};
  for (const [name, value] of Object.entries(headers)) {
    if (!STRIPPED_HEADERS.has(name.toLowerCase())) stripped[name] = value;
  }
  return stripped;
}

/**
 * Build a ElastiCacheIntegrationsRequest from a prepared AWS request and middleware metadata.
 *
 * The SDK has already signed the request by this point. The SigV4 authentication headers and
 * the JS SDK-only headers are stripped, so ElastiCacheIntegrations receives an unsigned request.
 * Returns undefined for a body that is not bytes-like, such as a stream; the
 * caller passes the request through to the origin.
 */
export function awsToCacheRequest(
  request: unknown,
  service: string,
  commandName?: string,
): ElastiCacheIntegrationsRequest | undefined {
  if (!HttpRequest.isInstance(request)) {
    throw new ElastiCacheIntegrationsError(
      "ElastiCacheIntegrations middleware requires an AWS HttpRequest",
    );
  }
  if (typeof commandName !== "string" || !commandName.trim()) {
    throw new ElastiCacheIntegrationsError(
      "Malformed ElastiCacheIntegrations middleware command metadata",
    );
  }

  // AWS protocols encode operation names differently; middleware context
  // carries the command name uniformly.
  const operation = commandName.endsWith("Command") ? commandName.slice(0, -7) : commandName;
  if (!operation.trim()) {
    throw new ElastiCacheIntegrationsError(
      "Malformed ElastiCacheIntegrations middleware command metadata",
    );
  }

  const body = request.body;
  if (!isCarriableBody(body)) return undefined;

  return new ElastiCacheIntegrationsRequest({
    method: request.method,
    url: formatUrl(request),
    headers: stripSigningHeaders(request.headers),
    body: normalizeBody(body),
    metadata: new RequestMetadata({ service, operation }),
  });
}

/** Adapt a ElastiCacheIntegrationsResponse into the HttpResponse expected by the AWS SDK. */
export function cacheToHttpResponse(response: ElastiCacheIntegrationsResponse): HttpResponse {
  return new HttpResponse({
    statusCode: response.statusCode,
    headers: { ...response.headers },
    body: response.body,
  });
}

/**
 * The deserialize-step middleware. It builds the cache request from the signed request and its
 * command name, which only the middleware stack sees, and leaves it for the request handler.
 */
export function cacheMiddleware<Input extends object, Output extends object>(
  service: string,
  routed: WeakMap<object, ElastiCacheIntegrationsRequest>,
): DeserializeMiddleware<Input, Output> {
  return (next, context: HandlerExecutionContext) => async (args) => {
    const request = awsToCacheRequest(args.request, service, context.commandName);
    if (request !== undefined) routed.set(args.request as object, request);
    return next(args);
  };
}

/** The terminal step of an SDK call: send the request, given the SDK's per-call options. */
type SendRequest = (
  request: HttpRequest,
  options?: HttpHandlerOptions,
) => Promise<{ response: HttpResponse }>;

/**
 * Wrap the client's request handler, so a request the middleware prepared is served from the cache.
 * The request handler is the only step the SDK passes the call's `abortSignal` to. An abort is the
 * SDK's `AbortError` and never falls back.
 *
 * A Proxy rather than a patched `handle`, because an application may share one handler between
 * clients. Every other member reaches the real handler unchanged.
 */
export function cacheRequestHandler<THandler extends object>(
  handler: THandler,
  session: ElastiCacheIntegrationsSession,
  routed: WeakMap<object, ElastiCacheIntegrationsRequest>,
): THandler {
  const send: SendRequest = (httpRequest, options) =>
    (handler as unknown as { handle: SendRequest }).handle(httpRequest, options);
  const handle: SendRequest = async (httpRequest, options) => {
    const request = routed.get(httpRequest);
    if (request === undefined) return send(httpRequest, options);
    routed.delete(httpRequest);
    const next = () => send(httpRequest, options);

    try {
      const response = await session.request(request, options ?? {});
      if (response === undefined) return next();
      return { response: cacheToHttpResponse(response) };
    } catch (error) {
      if (!(error instanceof ElastiCacheIntegrationsError) || !session.isOpen) {
        throw error;
      }

      if (error instanceof ElastiCacheIntegrationsRequestNotSent) {
        return next();
      }

      const idempotent = isIdempotentOperation(
        request.metadata.service,
        request.metadata.operation,
      );
      if (error instanceof ElastiCacheIntegrationsRejectedError) {
        if (error.code === ORIGIN_ERROR && session.isConnectionHealthy && idempotent) {
          throw markRetryable(error);
        }
        throw error;
      }

      if (!session.isConnectionHealthy && error instanceof ElastiCacheIntegrationsConnectionLost) {
        throw markRetryable(error); // retryable error so the SDK decides whether to retry or not
      }
      if (!session.isConnectionHealthy && idempotent && isTransportFailure(error)) {
        throw markRetryable(error);
      }
      if (
        session.isConnectionHealthy &&
        idempotent &&
        error instanceof ElastiCacheIntegrationsTimeoutError
      ) {
        throw markRetryable(error);
      }
      throw error;
    }
  };
  return new Proxy(handler, {
    get(target, property) {
      if (property === "handle") return handle;
      const value: unknown = Reflect.get(target, property, target);
      return typeof value === "function" ? value.bind(target) : value;
    },
  });
}

export function installCacheMiddleware<Input extends object, Output extends object>(
  client: { middlewareStack: MiddlewareStack<Input, Output> },
  service: string,
  routed: WeakMap<object, ElastiCacheIntegrationsRequest>,
): void {
  client.middlewareStack.addRelativeTo(cacheMiddleware<Input, Output>(service, routed), {
    name: CACHE_MIDDLEWARE,
    relation: "after",
    toMiddleware: "deserializerMiddleware",
  });
}

/**
 * Drop the handlers a `cacheMiddleware: true` client resolved before its stack changed.
 *
 * Smithy caches each command's resolved middleware chain in the private `handlers` field and
 * clears it the same way in its own destroy(). Without this, a command sent before install() keeps
 * its cache-free chain, and one sent before shutdown() keeps a chain that calls the closed session.
 */
function clearCachedHandlers(client: ElastiCacheIntegrationsCompatibleClient): void {
  Reflect.deleteProperty(client, "handlers");
}

/**
 * Key for the handle attached to a client, so a second install can be refused.
 *
 * Symbol.for() returns the same symbol in every copy of this module, so an app that loads both the
 * ESM and CommonJS builds still sees one install per client.
 */
const HANDLE = Symbol.for("aws-elasticache-integration-client.handle");

/** Return the handle already attached to `sdkClient`, or undefined if there is none. */
export function installedHandle(sdkClient: object): ElastiCacheIntegrationsHandle | undefined {
  return Reflect.get(sdkClient, HANDLE) as ElastiCacheIntegrationsHandle | undefined;
}

/** Attaches ElastiCacheIntegrations to one AWS SDK client and detaches it cleanly. */
export class ElastiCacheIntegrationsHandle {
  installed = false;
  private destroyed = false;
  private sdkDestroy: (() => void) | undefined;
  private restoreRequestHandler: (() => void) | undefined;

  constructor(
    readonly sdkClient: ElastiCacheIntegrationsCompatibleClient,
    readonly session: ElastiCacheIntegrationsSession,
    private readonly service: string,
  ) {
    this.install();
  }

  /** Install ElastiCacheIntegrations middleware and override client destroy() for transparent teardown. */
  install(): void {
    if (this.installed || this.destroyed) return;
    const routed = new WeakMap<object, ElastiCacheIntegrationsRequest>();
    installCacheMiddleware(this.sdkClient, this.service, routed);
    this.restoreRequestHandler = this.wrapRequestHandler(routed);
    clearCachedHandlers(this.sdkClient);
    this.sdkDestroy = this.sdkClient.destroy;
    this.sdkClient.destroy = this.destroy;
    Reflect.set(this.sdkClient, HANDLE, this);
    this.installed = true;
  }

  /**
   * Serve prepared requests through the client's request handler, including one the application
   * assigns to `config.requestHandler` after install: the property becomes an accessor that wraps
   * whatever is set. Returns a function that puts back a plain property holding the latest handler.
   */
  private wrapRequestHandler(routed: WeakMap<object, ElastiCacheIntegrationsRequest>): () => void {
    const config = this.sdkClient.config;
    const original = Object.getOwnPropertyDescriptor(config, "requestHandler");
    let sdkHandler = config.requestHandler;
    let wrapped = cacheRequestHandler(sdkHandler, this.session, routed);
    Object.defineProperty(config, "requestHandler", {
      configurable: true,
      enumerable: original?.enumerable ?? true,
      get: () => wrapped,
      set: (handler: object) => {
        sdkHandler = handler;
        wrapped = cacheRequestHandler(handler, this.session, routed);
      },
    });
    return () => {
      Object.defineProperty(config, "requestHandler", {
        configurable: true,
        enumerable: original?.enumerable ?? true,
        writable: true,
        value: sdkHandler,
      });
    };
  }

  /** Remove middleware, restore destroy(), and close ElastiCacheIntegrations without destroying the SDK client. */
  shutdown(): void {
    if (!this.installed) return;
    this.sdkClient.middlewareStack.remove(CACHE_MIDDLEWARE);
    this.restoreRequestHandler!();
    clearCachedHandlers(this.sdkClient);
    this.sdkClient.destroy = this.sdkDestroy!;
    Reflect.deleteProperty(this.sdkClient, HANDLE);
    this.session.destroy();
    this.installed = false;
  }

  /** Replace client destroy(): shut ElastiCacheIntegrations down, then destroy the SDK client normally. */
  private readonly destroy = (): void => {
    if (this.destroyed) return;
    this.destroyed = true;
    const sdkDestroy = this.sdkDestroy!;
    try {
      this.shutdown();
    } finally {
      try {
        sdkDestroy.call(this.sdkClient);
      } finally {
        // Put the SDK's own destroy() back, so a later install does not save this inert one as
        // "the SDK's" and stop the SDK client from ever being destroyed.
        this.sdkClient.destroy = sdkDestroy;
      }
    }
  };
}
