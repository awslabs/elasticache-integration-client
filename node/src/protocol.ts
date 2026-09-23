import { TextDecoder } from "node:util";

import { PhotonError, PhotonProtocolError, PhotonRejectedError } from "./errors.js";
import { PhotonResponse, ResolvedConfig, type PhotonRequest } from "./models.js";
import { RespError, type RespValue } from "./resp.js";

/**
 * RESP3 protocol layer for the Photon client.
 *
 * Owns the **Photon protocol semantics**: header normalization + request/response
 * (de)serialization, the two Photon commands (`photon.config`, `photon.request`), and
 * interpreting the cache's replies into `ResolvedConfig` / `PhotonResponse`. The generic RESP3
 * **wire codec** (`encodeResp`, `decodeResp`, `RespValue`/`RespError`) lives in `resp.ts`.
 *
 * The RESP3 wire format is fixed by the client-cache contract.
 */
export const CONFIG_COMMAND = "photon.config";
export const REQUEST_COMMAND = "photon.request";
export const PUSH_KIND_RESPONSE = Buffer.from("photon.response", "ascii");

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
  throw new PhotonError(`Cannot convert value of type ${typeof value} to bytes`);
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
    throw new PhotonProtocolError("Invalid UTF-8 in response headers", { cause });
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
      throw new PhotonProtocolError(`Invalid status code: ${String(raw)}`);
    }
    return Math.trunc(raw);
  }

  let text: string;
  try {
    text = valueAsText(raw);
  } catch (cause) {
    throw new PhotonProtocolError("Invalid UTF-8 in response status", { cause });
  }
  // Accept an integer status string with optional surrounding whitespace, such as " 200 ".
  // Decimal, exponent, and other numeric formats are rejected.
  if (!/^\s*[+-]?\d+\s*$/.test(text)) {
    throw new PhotonProtocolError(`Invalid status code: ${String(raw)}`);
  }
  const value = Number(text);
  if (!Number.isFinite(value)) {
    throw new PhotonProtocolError(`Invalid status code: ${String(raw)}`);
  }
  return value;
}

/**
 * Build a PhotonResponse from the already-parsed RESP array elements.
 *
 * Expects `[status:int, headers:Buffer, body:Buffer]`. Validates the status code is in the 100-599
 * range. Raises `PhotonError` on a malformed shape or out-of-range status.
 */
export function deserializeResponse(elements: readonly unknown[] | null): PhotonResponse {
  if (!Array.isArray(elements) || elements.length !== 3) {
    throw new PhotonProtocolError(
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
    throw new PhotonProtocolError(
      `Status code out of range (${MIN_STATUS}-${MAX_STATUS}): ${status}`,
    );
  }
  if (!Buffer.isBuffer(headersRaw) || !Buffer.isBuffer(bodyRaw)) {
    throw new PhotonProtocolError(
      `Malformed response elements: headers=${describeType(headersRaw)}, body=${describeType(bodyRaw)}`,
    );
  }

  return new PhotonResponse({
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
 * Build the `photon.config` command from an overrides object.
 *
 * Returns `["photon.config", [overridesJsonBytes]]`.
 */
export function serializeConfig(
  overrides: Record<string, unknown> | null | undefined,
): [string, [Buffer]] {
  const payload = escapeNonAscii(stringifyConfig(overrides ?? {}));
  return [CONFIG_COMMAND, [Buffer.from(payload, "utf8")]];
}

function stringifyConfig(overrides: Record<string, unknown>): string {
  try {
    const serialized = JSON.stringify(overrides, (_key, value: unknown) => {
      if (typeof value === "number" && !Number.isFinite(value)) {
        throw new PhotonError("photon.config overrides must contain finite numbers");
      }
      return value;
    });
    if (serialized === undefined) {
      throw new PhotonError("photon.config overrides must be JSON-serializable");
    }
    return serialized;
  } catch (cause) {
    if (cause instanceof PhotonError) throw cause;
    throw new PhotonError("photon.config overrides must be JSON-serializable", { cause });
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
 * Parse the `photon.config` reply (a JSON bulk string) into a ResolvedConfig.
 *
 * The reply is a JSON bulk string; a RESP-array shape is intentionally not supported.
 */
export function parseConfigResponse(value: RespValue): ResolvedConfig {
  if (value instanceof RespError) {
    throw new PhotonError(`photon.config failed: ${value.toString()}`);
  }
  if (!Buffer.isBuffer(value)) {
    throw new PhotonError(
      `Malformed config response: expected JSON bulk string, got ${
        value === null ? "null" : Array.isArray(value) ? "array" : typeof value
      }`,
    );
  }

  let text: string;
  try {
    text = STRICT_UTF8.decode(value);
  } catch (cause) {
    throw new PhotonError("Malformed config JSON: invalid UTF-8", { cause });
  }
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch (cause) {
    throw new PhotonError(`Malformed config JSON: ${text}`, { cause });
  }

  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) {
    throw new PhotonError(
      `Config JSON must be an object, got ${raw === null ? "null" : Array.isArray(raw) ? "array" : typeof raw}`,
    );
  }

  const object = raw as Record<string, unknown>;
  return new ResolvedConfig({
    version: object.version,
    refreshInterval: object.refresh_interval,
    raw: object,
  });
}

/**
 * Build the complete `photon.request` command from a PhotonRequest.
 *
 * Args are `[method, url, normalizedHeaders, body, metadata]`.
 */
export function serializeRequest(
  request: PhotonRequest,
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
 * Interpret a `photon.request` reply into a PhotonResponse.
 *
 * Expects a 3-element array `[status, headers, body]`.
 */
export function parseRequestResponse(value: RespValue): PhotonResponse {
  if (value instanceof RespError) {
    throw new PhotonRejectedError(value.code, `photon.request failed: ${value.toString()}`);
  }
  if (!Array.isArray(value)) {
    throw new PhotonProtocolError(
      `Malformed request response: expected array, got ${value === null ? "null" : typeof value}`,
    );
  }
  // Convert the validated [status, headers, body] array into a PhotonResponse
  // (also validates the status code range).
  return deserializeResponse(value);
}
