/**
 * AWS SDK v3 interception for the Photon client.
 *
 * The middleware receives the final serialized, signed HttpRequest, strips the
 * AWS SigV4 authentication headers, builds a PhotonRequest, calls PhotonSession,
 * and returns a synthetic HttpResponse. Predicate declines, explicit Photon
 * rejections, and indeterminate failures for idempotent operations return to
 * the SDK's origin pipeline while preserving its normal retry and
 * deserialization behavior.
 *
 * The SDK's real signer is left intact so requests can still be forwarded to
 * the origin with valid signatures; only the Photon path drops the auth
 * headers.
 */
import { formatUrl } from "@aws-sdk/core/util";
import { HttpRequest, HttpResponse } from "@smithy/core/protocols";
import type {
  AwsCredentialIdentityProvider,
  DeserializeMiddleware,
  HandlerExecutionContext,
  MiddlewareStack,
} from "@smithy/types";
import {
  PhotonConnectionError,
  PhotonConnectionLost,
  PhotonError,
  PhotonRejectedError,
  PhotonTimeoutError,
} from "./errors.js";
import { isIdempotentOperation } from "./idempotency.js";
import { PhotonRequest, RequestMetadata } from "./models.js";
import type { PhotonResponse } from "./models.js";
import type { PhotonSession } from "./session.js";

const PHOTON_MIDDLEWARE = "photon";
const ORIGIN_ERROR = "ORIGIN_ERROR";

type RetryableError = Error & { $retryable: Record<string, never> };

function markRetryable(error: Error): RetryableError {
  return Object.assign(error, { $retryable: {} });
}

function isTransportFailure(error: PhotonError): boolean {
  return (
    error instanceof PhotonTimeoutError ||
    error instanceof PhotonConnectionLost ||
    error instanceof PhotonConnectionError
  );
}

/** The part of an AWS SDK v3 client used by PhotonHandle. */
export interface PhotonCompatibleClient {
  config: { credentials: AwsCredentialIdentityProvider };
  middlewareStack: MiddlewareStack<any, any>;
  destroy(): void;
}

/** The prepared body as bytes, or undefined for a streaming body Photon cannot carry. */
function normalizeBody(body: unknown): Buffer | undefined {
  if (body == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(body)) return body;
  if (typeof body === "string") return Buffer.from(body, "utf8");
  if (body instanceof Uint8Array) return Buffer.from(body);
  return undefined;
}

/** AWS SigV4 authentication headers, removed before forwarding to Photon. */
const SIGV4_HEADERS = new Set(["authorization", "x-amz-date", "x-amz-security-token"]);

/** Copy request headers without the AWS SigV4 authentication headers. */
function stripSigningHeaders(headers: Record<string, string>): Record<string, string> {
  const stripped: Record<string, string> = {};
  for (const [name, value] of Object.entries(headers)) {
    if (!SIGV4_HEADERS.has(name.toLowerCase())) stripped[name] = value;
  }
  return stripped;
}

/**
 * Build a PhotonRequest from a prepared AWS request and middleware metadata.
 *
 * The SDK has already signed the request by this point; the SigV4
 * authentication headers are stripped so Photon receives an unsigned request.
 * Returns undefined for a streaming body, because serializing it into one
 * `photon.request` frame would consume the stream; the caller passes the
 * request through to the origin.
 */
export function awsToPhotonRequest(
  request: unknown,
  service: string,
  commandName?: string,
): PhotonRequest | undefined {
  if (!HttpRequest.isInstance(request)) {
    throw new PhotonError("Photon middleware requires an AWS HttpRequest");
  }
  if (typeof commandName !== "string" || !commandName.trim()) {
    throw new PhotonError("Malformed Photon middleware command metadata");
  }

  // AWS protocols encode operation names differently; middleware context
  // carries the command name uniformly.
  const operation = commandName.endsWith("Command") ? commandName.slice(0, -7) : commandName;
  if (!operation.trim()) {
    throw new PhotonError("Malformed Photon middleware command metadata");
  }

  const body = normalizeBody(request.body);
  if (body === undefined) return undefined;

  return new PhotonRequest({
    method: request.method,
    url: formatUrl(request),
    headers: stripSigningHeaders(request.headers),
    body,
    metadata: new RequestMetadata({ service, operation }),
  });
}

/** Adapt a PhotonResponse into the HttpResponse expected by the AWS SDK. */
export function photonToHttpResponse(response: PhotonResponse): HttpResponse {
  return new HttpResponse({
    statusCode: response.statusCode,
    headers: { ...response.headers },
    body: response.body,
  });
}

export function photonMiddleware<Input extends object, Output extends object>(
  service: string,
  session: PhotonSession,
): DeserializeMiddleware<Input, Output> {
  return (next, context: HandlerExecutionContext) => async (args) => {
    const request = awsToPhotonRequest(args.request, service, context.commandName);
    if (request === undefined) return next(args);

    try {
      const response = await session.request(request);
      if (response === undefined) return next(args);
      return { response: photonToHttpResponse(response) };
    } catch (error) {
      if (!(error instanceof PhotonError) || !session.isOpen) {
        throw error;
      }

      const idempotent = isIdempotentOperation(
        request.metadata.service,
        request.metadata.operation,
      );
      if (error instanceof PhotonRejectedError) {
        if (error.code === ORIGIN_ERROR && session.isConnectionHealthy && idempotent) {
          throw markRetryable(error);
        }
        throw error;
      }

      if (!session.isConnectionHealthy && idempotent && isTransportFailure(error)) {
        return next(args);
      }
      if (session.isConnectionHealthy && idempotent && error instanceof PhotonTimeoutError) {
        throw markRetryable(error);
      }
      throw error;
    }
  };
}

export function installPhotonMiddleware<Input extends object, Output extends object>(
  client: { middlewareStack: MiddlewareStack<Input, Output> },
  service: string,
  session: PhotonSession,
): void {
  client.middlewareStack.addRelativeTo(photonMiddleware<Input, Output>(service, session), {
    name: PHOTON_MIDDLEWARE,
    relation: "after",
    toMiddleware: "deserializerMiddleware",
  });
}

/** Attaches Photon to one AWS SDK client and detaches it cleanly. */
export class PhotonHandle {
  installed = false;
  private destroyed = false;
  private sdkDestroy: (() => void) | undefined;

  constructor(
    readonly sdkClient: PhotonCompatibleClient,
    readonly session: PhotonSession,
    private readonly service: string,
  ) {
    this.install();
  }

  /** Install Photon middleware and override client destroy() for transparent teardown. */
  install(): void {
    if (this.installed || this.destroyed) return;
    installPhotonMiddleware(this.sdkClient, this.service, this.session);
    this.sdkDestroy = this.sdkClient.destroy.bind(this.sdkClient);
    this.sdkClient.destroy = this.destroy;
    this.installed = true;
  }

  /** Remove middleware, restore destroy(), and close Photon without destroying the SDK client. */
  shutdown(): void {
    if (!this.installed) return;
    this.sdkClient.middlewareStack.remove(PHOTON_MIDDLEWARE);
    this.sdkClient.destroy = this.sdkDestroy!;
    this.session.destroy();
    this.installed = false;
  }

  /** Replace client destroy(): shut Photon down, then destroy the SDK client normally. */
  private readonly destroy = (): void => {
    if (this.destroyed) return;
    this.destroyed = true;
    const sdkDestroy = this.sdkDestroy!;
    try {
      this.shutdown();
    } finally {
      try {
        sdkDestroy();
      } finally {
        this.sdkClient.destroy = this.destroy;
      }
    }
  };
}
