import { PhotonError, PhotonProtocolError } from "./errors.js";

const CRLF = Buffer.from("\r\n");
/** Strict UTF-8 decoder shared by simple-string decoding and RESP-string-keyed correlation. */
export const STRICT_UTF8 = new TextDecoder("utf-8", { fatal: true });

/**
 * Generic RESP3 wire codec for the Photon client.
 *
 * Owns the RESP value types (`RespValue`, `RespError`), the framing functions (`encodeResp`,
 * `decodeResp`), and `IncompleteFrame`. `decodeResp` distinguishes a **truncated** frame
 * (more bytes are coming -> `IncompleteFrame`) from genuine **garbage** (unparseable input ->
 * `PhotonProtocolError`), so an incremental reader can tell "need more bytes" apart from "the
 * stream is corrupt".
 */
/** A normalized RESP error reply (`-CODE message`). */
export class RespError {
  readonly code: string;
  readonly message: string;

  constructor(code: string, message: string) {
    this.code = code;
    this.message = message;
  }

  /** Render the wire error without an extra trailing space. */
  toString(): string {
    return `${this.code} ${this.message}`.trim();
  }
}

/** A RESP3 push frame, kept distinct from ordinary arrays. */
export class RespPush {
  constructor(readonly items: RespValue[]) {}
}

// A decoded RESP value: integer, bigint, simple string, bulk string, array, map, error, push,
// or null. Safe RESP integers remain numbers; larger integer frames use bigint.
export type RespValue =
  | number
  | bigint
  | string
  | Buffer
  | RespValue[]
  | Map<RespValue, RespValue>
  | RespError
  | RespPush
  | null;

type Decoder = (data: Buffer, header: Buffer, afterHeader: number) => [RespValue, number];

/** Raised by `decodeResp` when the buffer holds only a partial frame (need more bytes). */
export class IncompleteFrame extends Error {
  constructor(message: string) {
    super(message);
    this.name = new.target.name;
  }
}

/**
 * Encode a command + binary args as a RESP3 array of bulk strings.
 *
 * Produces `*<N>\r\n$<len>\r\n<bytes>\r\n...`. A non-Buffer arg is programmer misuse and raises
 * `PhotonError`.
 */
export function encodeResp(command: string, ...args: Buffer[]): Buffer {
  const elements = [Buffer.from(command), ...args];
  const parts: Buffer[] = [Buffer.from(`*${elements.length}\r\n`)];

  for (const element of elements) {
    if (!Buffer.isBuffer(element)) {
      throw new PhotonError(`Command args must be bytes, got ${typeof element}`);
    }
    // Each element is written as "$<size>\r\n<data>\r\n"
    parts.push(Buffer.from(`$${element.length}\r\n`), element, CRLF);
  }

  return Buffer.concat(parts);
}

/** Parse a numeric RESP header, raising `PhotonProtocolError` on garbage. */
function parseInteger(header: Buffer, what: string): number | bigint {
  const text = header.toString("latin1");
  // RESP integers allow surrounding ASCII whitespace and an optional sign, but only decimal
  // digits; for example, " -42 " is accepted while "4.2" is rejected.
  if (!/^[\t\n\r\f\v ]*[+-]?\d+[\t\n\r\f\v ]*$/.test(text)) {
    throw new PhotonProtocolError(`Malformed RESP ${what}: ${header.toString()}`);
  }

  const value = BigInt(text);
  const number = Number(value);
  return Number.isSafeInteger(number) ? number : value;
}

/** Parse a RESP collection length, which must fit in a JavaScript number. */
function parseLength(header: Buffer, what: string): number {
  const value = parseInteger(header, what);
  if (typeof value === "bigint") {
    throw new PhotonProtocolError(`Malformed RESP ${what}: ${header.toString()}`);
  }
  return value;
}

/**
 * Decode a `+` simple string to text. Bulk strings remain binary-safe Buffers.
 *
 * Simple strings must be valid UTF-8; replacing invalid bytes would silently corrupt
 * values such as deferred request IDs, so malformed input is a protocol error.
 */
function decodeSimpleString(
  _data: Buffer,
  header: Buffer,
  afterHeader: number,
): [RespValue, number] {
  try {
    return [STRICT_UTF8.decode(header), afterHeader];
  } catch {
    throw new PhotonProtocolError(`Malformed RESP simple string: ${header.toString("latin1")}`);
  }
}

/** Decode a `:` integer. */
function decodeInteger(_data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  return [parseInteger(header, "integer"), afterHeader];
}

/** Decode a `-` error into a `RespError` (`-CODE message`). */
function decodeError(_data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  // Split "CODE message" at its first space; for example, "ERR bad" becomes ["ERR", "bad"].
  const [code = "", message = ""] = header.toString("utf8").split(/ (.*)/s, 2);
  return [new RespError(code, message), afterHeader];
}

/** Decode a `$` bulk string, or `null` for the `$-1` null bulk. */
function decodeBulkString(data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  const length = parseLength(header, "bulk length");
  if (length === -1) {
    return [null, afterHeader];
  }
  if (length < -1) {
    throw new PhotonProtocolError(`Malformed RESP bulk length: ${header.toString()}`);
  }

  const end = afterHeader + length;
  if (end + CRLF.length > data.length) {
    // body + trailing CRLF not all present yet
    throw new IncompleteFrame("bulk string body incomplete");
  }
  if (!data.subarray(end, end + CRLF.length).equals(CRLF)) {
    throw new PhotonProtocolError("Malformed RESP bulk string terminator");
  }
  return [data.subarray(afterHeader, end), end + CRLF.length];
}

/** Decode a `_` RESP3 null. No body - the header line must be empty. */
function decodeNull(_data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  if (header.length !== 0) {
    throw new PhotonProtocolError(`Malformed RESP null: ${header.toString()}`);
  }
  return [null, afterHeader];
}

/** Decode a `*` array, or `null` for the `*-1` null array. */
function decodeArray(data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  const count = parseLength(header, "array length");
  if (count === -1) {
    return [null, afterHeader];
  }
  if (count < -1) {
    throw new PhotonProtocolError(`Malformed RESP array length: ${header.toString()}`);
  }

  const items: RespValue[] = [];
  let position = afterHeader;
  for (let index = 0; index < count; index += 1) {
    // inner IncompleteFrame propagates
    const [item, nextPosition] = decodeResp(data, position);
    items.push(item);
    position = nextPosition;
  }
  return [items, position];
}

/** Decode a `>` push. Pushes cannot be null; their frame type remains observable to the reader. */
function decodePush(data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  const [items, position] = decodeArray(data, header, afterHeader);
  if (!Array.isArray(items)) {
    throw new PhotonProtocolError(`Malformed RESP push length: ${header.toString()}`);
  }
  return [new RespPush(items), position];
}

/** Decode a `%` map. Keys and values may each be any RESP value. */
function decodeMap(data: Buffer, header: Buffer, afterHeader: number): [RespValue, number] {
  const count = parseLength(header, "map length");
  if (count < 0) {
    throw new PhotonProtocolError(`Malformed RESP map length: ${header.toString()}`);
  }

  const entries = new Map<RespValue, RespValue>();
  let position = afterHeader;
  for (let index = 0; index < count; index += 1) {
    const [key, afterKey] = decodeResp(data, position);
    const [value, afterValue] = decodeResp(data, afterKey);
    entries.set(key, value);
    position = afterValue;
  }
  return [entries, position];
}

// Prefix -> decoder dispatch.
const DECODERS: Record<string, Decoder> = {
  "+": decodeSimpleString,
  ":": decodeInteger,
  "-": decodeError,
  $: decodeBulkString,
  _: decodeNull,
  "*": decodeArray,
  ">": decodePush,
  "%": decodeMap,
};

/** Decode a single RESP3 value beginning at `start`. */
export function decodeResp(data: Buffer, start = 0): [RespValue, number] {
  if (start >= data.length) {
    throw new IncompleteFrame("empty buffer");
  }

  // Read the type prefix (+ - : $ * > %)
  const prefix = data.subarray(start, start + 1).toString("ascii");
  // Find the end of the header line ("$3", ":42", "+OK", ...)
  const lineEnd = data.indexOf(CRLF, start + 1);
  if (lineEnd === -1) {
    throw new IncompleteFrame("header line not yet terminated");
  }

  // Extract the header
  const header = data.subarray(start + 1, lineEnd);
  const afterHeader = lineEnd + CRLF.length;

  // Pick the decoder for this type and decode
  const decoder = DECODERS[prefix];
  if (decoder === undefined) {
    throw new PhotonProtocolError(`Unsupported RESP prefix: ${JSON.stringify(prefix)}`);
  }
  return decoder(data, header, afterHeader);
}
