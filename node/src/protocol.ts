import { TextDecoder } from "node:util";

import {
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
} from "./errors.js";
import {
  ElastiCacheIntegrationsResponse,
  ResolvedConfig,
  type ElastiCacheIntegrationsRequest,
} from "./models.js";
import { RespError, type RespValue } from "./resp.js";

/**
 * RESP3 protocol layer for the ElastiCacheIntegrations client.
 *
 * Owns the **ElastiCacheIntegrations protocol semantics**: header normalization + request/response
 * (de)serialization, the two ElastiCacheIntegrations commands (`aws.config`, `aws.request`), and
 * interpreting the cache's replies into `ResolvedConfig` / `ElastiCacheIntegrationsResponse`. The generic RESP3
 * **wire codec** (`encodeResp`, `decodeResp`, `RespValue`/`RespError`) lives in `resp.ts`.
 *
 * The RESP3 wire format is fixed by the client-cache contract.
 */
export const CONFIG_COMMAND = "aws.config";
export const REQUEST_COMMAND = "aws.request";
export const PUSH_KIND_RESPONSE = Buffer.from("aws.response", "ascii");

/**
 * Protocol version this client speaks, sent in every `aws.config` overrides payload.
 *
 * Mandatory server-side: an exchange without it is rejected rather than defaulted. The cache
 * answers in the newest version it supports at or below this one, so a client ahead of its
 * proxy is clamped down rather than refused -- and learns which version it got from the
 * resolved config's `version`, which carries the *negotiated* version, not an echo of this.
 */
export const PROTOCOL_VERSION = "1.0";
/** RESP error code the proxy returns when it rejects the `aws.config` overrides. */
export const CONFIG_INVALID_CODE = "CONFIG_INVALID";

const MIN_STATUS = 100;
const MAX_STATUS = 599;

// Headers excluded from the normalized form (case-insensitive). Auth/volatile headers must not
// affect the cache key or be forwarded.
const EXCLUDED_HEADERS = new Set([
  "authorization",
  "x-amz-date",
  "x-amz-security-token",
  "x-amz-request-id",
  "amz-sdk-request",
]);

const STRICT_UTF8 = new TextDecoder("utf-8", { fatal: true });

/** Coerce a wire value to text, decoding buffers as UTF-8 and rejecting malformed data. */
function valueAsText(value: unknown): string {
  if (Buffer.isBuffer(value)) {
    return STRICT_UTF8.decode(value);
  }
  return String(value);
}

/** Normalize a str/Buffer/null/undefined value to bytes (UTF-8). */
export function toBuffer(value: unknown): Buffer {
  if (value === null || value === undefined) {
    return Buffer.alloc(0);
  }
  if (Buffer.isBuffer(value)) {
    return value;
  }
  if (typeof value === "string") {
    return Buffer.from(value, "utf8");
  }
  throw new ElastiCacheIntegrationsError(`Cannot convert value of type ${typeof value} to bytes`);
}

/** Coerce a value to text, decoding buffers as UTF-8 with replacement. */
export function toString(value: unknown): string {
  return Buffer.isBuffer(value) ? value.toString("utf8") : String(value);
}

/**
 * Produce the deterministic normalized header string as bytes.
 *
 * Lowercase names, drop excluded headers, trim values, sort by name then value, and join
 * `name:value` with LF. Returns an empty `Buffer` when no headers remain.
 */
export function normalizeHeaders(headers: Record<string, unknown> | null | undefined): Buffer {
  const normalized = Object.entries(headers ?? {})
    .map(([name, value]) => [name.trim().toLowerCase(), toString(value).trim()] as const)
    .filter(([name]) => !EXCLUDED_HEADERS.has(name))
    .sort(([leftName, leftValue], [rightName, rightValue]) => {
      if (leftName !== rightName) return leftName < rightName ? -1 : 1;
      if (leftValue !== rightValue) return leftValue < rightValue ? -1 : 1;
      return 0;
    })
    .map(([name, value]) => `${name}:${value}`)
    .join("\n");

  return Buffer.from(normalized, "utf8");
}

/**
 * Format request metadata as `service=<service>&op=<operation>`.
 *
 * Missing values render as empty strings (for example, `service=&op=`).
 */
export function formatMetadata(service?: string, operation?: string): Buffer {
  return Buffer.from(`service=${service || ""}&op=${operation || ""}`, "utf8");
}

/** Parse a normalized header blob (LF-separated `name:value`) into a record. */
function parseHeaders(raw: Buffer): Record<string, string> {
  let text: string;
  try {
    text = valueAsText(raw);
  } catch (cause) {
    throw new ElastiCacheIntegrationsProtocolError("Invalid UTF-8 in response headers", { cause });
  }
  const headers: Record<string, string> = {};

  for (const line of text.split("\n")) {
    if (!line) {
      continue;
    }
    const separator = line.indexOf(":");
    if (separator === -1) {
      // No separator: skip malformed line rather than guessing.
      continue;
    }
    headers[line.slice(0, separator).trim()] = line.slice(separator + 1).trim();
  }

  return headers;
}

/** Parse a response status value, rejecting malformed values. */
function parseStatus(raw: unknown): number {
  if (typeof raw === "number") {
    if (!Number.isFinite(raw)) {
      throw new ElastiCacheIntegrationsProtocolError(`Invalid status code: ${String(raw)}`);
    }
    return Math.trunc(raw);
  }

  let text: string;
  try {
    text = valueAsText(raw);
  } catch (cause) {
    throw new ElastiCacheIntegrationsProtocolError("Invalid UTF-8 in response status", { cause });
  }
  // Accept an integer status string with optional surrounding whitespace, such as " 200 ".
  // Decimal, exponent, and other numeric formats are rejected.
  if (!/^\s*[+-]?\d+\s*$/.test(text)) {
    throw new ElastiCacheIntegrationsProtocolError(`Invalid status code: ${String(raw)}`);
  }
  const value = Number(text);
  if (!Number.isFinite(value)) {
    throw new ElastiCacheIntegrationsProtocolError(`Invalid status code: ${String(raw)}`);
  }
  return value;
}

/**
 * Build a ElastiCacheIntegrationsResponse from the already-parsed RESP array elements.
 *
 * Expects `[status:int, headers:Buffer, body:Buffer]`. Validates the status code is in the 100-599
 * range. Raises `ElastiCacheIntegrationsError` on a malformed shape or out-of-range status.
 */
export function deserializeResponse(
  elements: readonly unknown[] | null,
): ElastiCacheIntegrationsResponse {
  if (!Array.isArray(elements) || elements.length !== 3) {
    throw new ElastiCacheIntegrationsProtocolError(
      `Malformed response: expected 3 elements [status, headers, body], got ${
        elements === null
          ? "None"
          : Array.isArray(elements)
            ? elements.length
            : describeType(elements)
      }`,
    );
  }

  const [statusRaw, headersRaw, bodyRaw] = elements;
  const status = parseStatus(statusRaw);
  if (status < MIN_STATUS || status > MAX_STATUS) {
    throw new ElastiCacheIntegrationsProtocolError(
      `Status code out of range (${MIN_STATUS}-${MAX_STATUS}): ${status}`,
    );
  }
  if (!Buffer.isBuffer(headersRaw) || !Buffer.isBuffer(bodyRaw)) {
    throw new ElastiCacheIntegrationsProtocolError(
      `Malformed response elements: headers=${describeType(headersRaw)}, body=${describeType(bodyRaw)}`,
    );
  }

  return new ElastiCacheIntegrationsResponse({
    statusCode: status,
    headers: parseHeaders(headersRaw),
    body: bodyRaw,
  });
}

function describeType(value: unknown): string {
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (Buffer.isBuffer(value)) return "bytes";
  return typeof value;
}

/**
 * Build the `aws.config` command from an overrides object.
 *
 * Returns `["aws.config", [overridesJsonBytes]]`. `null`/`undefined` means "no overrides
 * argument at all" and returns `["aws.config", []]`, which is how a refresh asks the cache
 * for the current config -- that form carries no JSON, so it carries no version either, and
 * the cache answers it from the version it recorded on the last overrides exchange.
 *
 * Every object form carries {@link PROTOCOL_VERSION}, which the cache requires. It is spread
 * in first so a caller's own `version` wins, which is how a test drives a version this client
 * would not otherwise send. An empty object is therefore sent as `{"version":"1.0"}` rather
 * than `{}` -- same "use your defaults" intent, now with the mandatory field.
 */
export function serializeConfig(
  overrides: Record<string, unknown> | null | undefined,
): [string, Buffer[]] {
  if (overrides === null || overrides === undefined) {
    return [CONFIG_COMMAND, []];
  }
  const payload = escapeNonAscii(stringifyConfig({ version: PROTOCOL_VERSION, ...overrides }));
  return [CONFIG_COMMAND, [Buffer.from(payload, "utf8")]];
}

export function stringifyConfig(overrides: Record<string, unknown>): string {
  try {
    const serialized = JSON.stringify(overrides, (_key, value: unknown) => {
      if (typeof value === "number" && !Number.isFinite(value)) {
        throw new ElastiCacheIntegrationsError("aws.config overrides must contain finite numbers");
      }
      return value;
    });
    if (serialized === undefined) {
      throw new ElastiCacheIntegrationsError("aws.config overrides must be JSON-serializable");
    }
    return serialized;
  } catch (cause) {
    if (cause instanceof ElastiCacheIntegrationsError) throw cause;
    throw new ElastiCacheIntegrationsError("aws.config overrides must be JSON-serializable", {
      cause,
    });
  }
}

/** Escape non-ASCII UTF-16 code units, such as `é`, as Python `json.dumps` does by default. */
function escapeNonAscii(value: string): string {
  // Match UTF-16 code units from U+0080 through U+FFFF; for example, "é" becomes "\\u00e9".
  return value.replace(
    /[\u0080-\uffff]/g,
    (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
}

/**
 * Parse the `aws.config` reply (a JSON bulk string) into a ResolvedConfig.
 *
 * The reply is a JSON bulk string; a RESP-array shape is intentionally not supported.
 */
export function parseConfigResponse(value: RespValue): ResolvedConfig {
  if (value instanceof RespError) {
    if (value.code === CONFIG_INVALID_CODE) {
      throw new ElastiCacheIntegrationsOverridesRejected(
        `aws.config rejected: ${value.toString()}`,
      );
    }
    throw new ElastiCacheIntegrationsError(`aws.config failed: ${value.toString()}`);
  }
  if (!Buffer.isBuffer(value)) {
    throw new ElastiCacheIntegrationsError(
      `Malformed config response: expected JSON bulk string, got ${
        value === null ? "null" : Array.isArray(value) ? "array" : typeof value
      }`,
    );
  }

  let text: string;
  try {
    text = STRICT_UTF8.decode(value);
  } catch (cause) {
    throw new ElastiCacheIntegrationsError("Malformed config JSON: invalid UTF-8", { cause });
  }
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (cause) {
    throw new ElastiCacheIntegrationsError(`Malformed config JSON: ${text}`, { cause });
  }

  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    throw new ElastiCacheIntegrationsError(
      `Config JSON must be an object, got ${raw === null ? "null" : Array.isArray(raw) ? "array" : typeof raw}`,
    );
  }

  const object = raw as Record<string, unknown>;
  return new ResolvedConfig({
    version: object.version,
    refreshInterval: parseRefreshInterval(object.refresh_interval),
    raw: object,
  });
}

/**
 * Validate the `refresh_interval` field: required, an integer number of seconds, and positive.
 *
 * The value feeds the config refresher's jittered delay arithmetic, so a
 * missing, wrongly typed, or non-positive value is treated as a malformed config rather than
 * deferred to a background timer.
 */
function parseRefreshInterval(value: unknown): number {
  if (value === undefined || value === null) {
    throw new ElastiCacheIntegrationsError("Config JSON missing required field 'refresh_interval'");
  }
  if (typeof value !== "number" || !Number.isInteger(value)) {
    throw new ElastiCacheIntegrationsError(
      `Invalid 'refresh_interval': expected integer, got ${typeof value}: ${JSON.stringify(value) ?? String(value)}`,
    );
  }
  if (value <= 0) {
    throw new ElastiCacheIntegrationsError(
      `Invalid 'refresh_interval': must be positive, got ${value}`,
    );
  }
  return value;
}

/**
 * Build the complete `aws.request` command from a ElastiCacheIntegrationsRequest.
 *
 * Args are `[method, url, normalizedHeaders, body, metadata]`.
 */
export function serializeRequest(
  request: ElastiCacheIntegrationsRequest,
): [string, [Buffer, Buffer, Buffer, Buffer, Buffer]] {
  return [
    REQUEST_COMMAND,
    [
      toBuffer(request.method),
      toBuffer(request.url),
      normalizeHeaders(request.headers),
      toBuffer(request.body),
      formatMetadata(request.metadata.service, request.metadata.operation),
    ],
  ];
}

/**
 * Interpret a `aws.request` reply into a ElastiCacheIntegrationsResponse.
 *
 * Expects a 3-element array `[status, headers, body]`.
 */
export function parseRequestResponse(value: RespValue): ElastiCacheIntegrationsResponse {
  if (value instanceof RespError) {
    throw new ElastiCacheIntegrationsRejectedError(
      value.code,
      `aws.request failed: ${value.toString()}`,
    );
  }
  if (!Array.isArray(value)) {
    throw new ElastiCacheIntegrationsProtocolError(
      `Malformed request response: expected array, got ${value === null ? "null" : typeof value}`,
    );
  }
  // Convert the validated [status, headers, body] array into a ElastiCacheIntegrationsResponse
  // (also validates the status code range).
  return deserializeResponse(value);
}
