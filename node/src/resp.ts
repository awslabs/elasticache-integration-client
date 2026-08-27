import { PhotonError, PhotonProtocolError } from "./errors.js";

const CRLF = Buffer.from("\r\n");

/**
 * Generic RESP3 wire codec for the Photon client.
 *
 * Owns the RESP value types (`RespValue`, `RespError`), the framing functions (`encodeResp`,
 * `decodeResp`), and `IncompleteFrameError`. `decodeResp` distinguishes a **truncated** frame
 * (more bytes are coming -> `IncompleteFrameError`) from genuine **garbage** (unparseable input ->
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

// A decoded RESP value: integer, bulk/simple string (Buffer), array, or error.
// Safe RESP integers remain numbers; larger integer frames use bigint.
export type RespValue = number | bigint | Buffer | RespValue[] | RespError | null;

type Decoder = (data: Buffer, header: Buffer, afterHeader: number) => [RespValue, number];

/** Raised by `decodeResp` when the buffer holds only a partial frame (need more bytes). */
export class IncompleteFrameError extends Error {
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

/** Decode a `+` simple string. The raw header bytes are the value. */
function decodeSimpleString(
  _data: Buffer,
  header: Buffer,
  afterHeader: number,
): [RespValue, number] {
  return [header, afterHeader];
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
    throw new IncompleteFrameError("bulk string body incomplete");
  }
  if (!data.subarray(end, end + CRLF.length).equals(CRLF)) {
    throw new PhotonProtocolError("Malformed RESP bulk string terminator");
  }
  return [data.subarray(afterHeader, end), end + CRLF.length];
}

/** Decode a `*` array or `>` push, or `null` for the `*-1` null array. */
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
    // inner IncompleteFrameError propagates
    const [item, nextPosition] = decodeResp(data, position);
    items.push(item);
    position = nextPosition;
  }
  return [items, position];
}

// Prefix -> decoder dispatch. `*` (array) and `>` (push) share the same array semantics.
const DECODERS: Record<string, Decoder> = {
  "+": decodeSimpleString,
  ":": decodeInteger,
  "-": decodeError,
  $: decodeBulkString,
  "*": decodeArray,
  ">": decodeArray,
};

/** Decode a single RESP3 value beginning at `start`. */
export function decodeResp(data: Buffer, start = 0): [RespValue, number] {
  if (start >= data.length) {
    throw new IncompleteFrameError("empty buffer");
  }

  // Read the type prefix (+ - : $ * >)
  const prefix = data.subarray(start, start + 1).toString("ascii");
  // Find the end of the header line ("$3", ":42", "+OK", ...)
  const lineEnd = data.indexOf(CRLF, start + 1);
  if (lineEnd === -1) {
    throw new IncompleteFrameError("header line not yet terminated");
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
