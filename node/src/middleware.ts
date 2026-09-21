/**
 * AWS SDK v3 interception for the Photon client.
 *
 * The middleware receives the final serialized, signed HttpRequest, strips the
 * AWS SigV4 authentication headers, builds a PhotonRequest, calls PhotonSession,
 * and returns a synthetic HttpResponse. Photon failures for idempotent
 * operations and predicate declines return to the SDK's origin pipeline while
 * preserving its normal retry and deserialization behavior.
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
import { SMITHY_CONTEXT_KEY } from "@smithy/types";

import { PhotonError } from "./errors.js";
import { PhotonRequest, RequestMetadata } from "./models.js";
import type { PhotonResponse } from "./models.js";
import type { PhotonSession } from "./session.js";

const PHOTON_MIDDLEWARE = "photon";
const IDEMPOTENT_TRAIT_MASK = 0b10;
const IDEMPOTENT_HTTP_METHODS = new Set(["DELETE", "GET", "HEAD", "OPTIONS", "PUT", "TRACE"]);

/** The part of an AWS SDK v3 client used by PhotonHandle. */
export interface PhotonCompatibleClient {
  config: { credentials: AwsCredentialIdentityProvider };
  middlewareStack: MiddlewareStack<any, any>;
  destroy(): void;
}

function normalizeBody(body: unknown): Buffer {
  if (body == null) return Buffer.alloc(0);
  if (Buffer.isBuffer(body)) return body;
  if (typeof body === "string") return Buffer.from(body, "utf8");
  if (body instanceof Uint8Array) return Buffer.from(body);
  throw new PhotonError("Streaming AWS request bodies are not supported");
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
 */
export function awsToPhotonRequest(
  request: unknown,
  service: string,
  commandName?: string,
): PhotonRequest {
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

  return new PhotonRequest({
    method: request.method,
    url: formatUrl(request),
    headers: stripSigningHeaders(request.headers),
    body: normalizeBody(request.body),
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

function operationTraits(schema: unknown): unknown {
  if (Array.isArray(schema)) return schema[3];
  if (schema !== null && typeof schema === "object") {
    return (schema as { traits?: unknown }).traits;
  }
  return undefined;
}

/**
 * Prefer Smithy operation metadata because AWS JSON protocols commonly use POST
 * for both read and write operations. HTTP method semantics cover generic
 * clients that do not provide an operation schema.
 */
function isIdempotentRequest(request: PhotonRequest, context: HandlerExecutionContext): boolean {
  const commandSchema = context[SMITHY_CONTEXT_KEY]?.commandInstance?.schema;
  if (commandSchema !== undefined) {
    const traits = operationTraits(commandSchema);
    if (typeof traits === "number") return (traits & IDEMPOTENT_TRAIT_MASK) !== 0;
    return (
      traits !== null &&
      typeof traits === "object" &&
      (traits as { idempotent?: unknown }).idempotent === 1
    );
  }
  return IDEMPOTENT_HTTP_METHODS.has(request.method.toUpperCase());
}

export function photonMiddleware<Input extends object, Output extends object>(
  service: string,
  session: PhotonSession,
): DeserializeMiddleware<Input, Output> {
  return (next, context: HandlerExecutionContext) => async (args) => {
    const request = awsToPhotonRequest(args.request, service, context.commandName);

    try {
      const response = await session.request(request);
      if (response === undefined) return next(args);
      return { response: photonToHttpResponse(response) };
    } catch (error) {
      if (error instanceof PhotonError && session.isOpen && isIdempotentRequest(request, context)) {
        return next(args);
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
