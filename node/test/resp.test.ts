/** Tests for the standalone RESP3 wire codec. */
import { describe, expect, it } from "vitest";

import { PhotonError, PhotonProtocolError } from "../src/errors.js";
import { decodeResp, encodeResp, IncompleteFrame, RespError, RespPush } from "../src/resp.js";

const CRLF = "\r\n";

describe("RESP codec", () => {
  it("incomplete frame is not a PhotonError", () => {
    // IncompleteFrame ("need more bytes") must be a plain Error, not a PhotonError,
    // so an incremental reader can distinguish truncation from corruption.
    const error = new IncompleteFrame("partial");

    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(PhotonError);
  });

  it("encode/decode round trip", () => {
    // Encoding a command then decoding it yields the original elements and
    // consumes the whole buffer.
    const encoded = encodeResp("photon.request", Buffer.from("POST"), Buffer.from("body"));

    expect(decodeResp(encoded)).toEqual([
      [Buffer.from("photon.request"), Buffer.from("POST"), Buffer.from("body")],
      encoded.length,
    ]);
  });

  it("encode command RESP3 array framing", () => {
    // Verifies the exact RESP3 byte framing (*N, $len, CRLFs) produced by the encoder.
    expect(encodeResp("photon.request", Buffer.from("POST"), Buffer.from("https://x/"))).toEqual(
      Buffer.from(
        `*3${CRLF}$14${CRLF}photon.request${CRLF}$4${CRLF}POST${CRLF}$10${CRLF}https://x/${CRLF}`,
      ),
    );
  });

  it("encode command rejects non-buffer arg", () => {
    // Passing a string argument (programmer misuse) is rejected.
    expect(() => encodeResp("photon.request", "not-bytes" as never)).toThrow(PhotonError);
  });

  it("preserves RESP integers beyond Number.MAX_SAFE_INTEGER", () => {
    // Large RESP integers remain bigint values instead of losing precision.
    expect(decodeResp(Buffer.from(`:9223372036854775807${CRLF}`))).toEqual([
      9223372036854775807n,
      22,
    ]);
  });

  it("decodes a simple string", () => {
    // Simple strings remain distinguishable from binary-safe bulk strings.
    expect(decodeResp(Buffer.from(`+OK${CRLF}`))).toEqual(["OK", 5]);
  });

  it("decodes an integer", () => {
    // Decodes a : integer.
    expect(decodeResp(Buffer.from(`:200${CRLF}`))).toEqual([200, 6]);
  });

  it("decodes an error frame", () => {
    // Decodes a - error into a RespError with code/message split on the first space.
    const [error, next] = decodeResp(Buffer.from(`-ERR something bad${CRLF}`));
    expect(error).toEqual(new RespError("ERR", "something bad"));
    expect(next).toBe(20);
  });

  it("decodes a bulk string", () => {
    // Decodes a $ bulk string of declared length.
    expect(decodeResp(Buffer.from(`$5${CRLF}hello${CRLF}`))).toEqual([Buffer.from("hello"), 11]);
  });

  it("decodes a null bulk string", () => {
    // Decodes a $-1 null bulk string to null.
    expect(decodeResp(Buffer.from(`$-1${CRLF}`))).toEqual([null, 5]);
  });

  it("decodes an array", () => {
    // Decodes a *3 array containing an int and two bulk strings.
    expect(
      decodeResp(Buffer.from(`*3${CRLF}:200${CRLF}$4${CRLF}hdrs${CRLF}$4${CRLF}body${CRLF}`)),
    ).toEqual([[200, Buffer.from("hdrs"), Buffer.from("body")], 30]);
  });

  it("decodes a push as a distinct RESP value", () => {
    expect(decodeResp(Buffer.from(`>2${CRLF}:1${CRLF}:2${CRLF}`))).toEqual([
      new RespPush([1, 2]),
      12,
    ]);
  });

  it("decodes a photon.response push with a decimal request id", () => {
    const frame = Buffer.from(
      `>3${CRLF}$15${CRLF}photon.response${CRLF}:42${CRLF}*1${CRLF}:200${CRLF}`,
    );

    const [push, consumed] = decodeResp(frame);

    expect(push).toEqual(new RespPush([Buffer.from("photon.response"), 42, [200]]));
    expect(consumed).toBe(frame.length);
  });

  it("decodes a null array", () => {
    // Decodes a *-1 null array to null.
    expect(decodeResp(Buffer.from(`*-1${CRLF}`))).toEqual([null, 5]);
  });

  it.each([
    Buffer.from(""), // empty buffer
    Buffer.from("+missing-crlf"), // header with no terminating CRLF
    Buffer.from(`$5${CRLF}hell`), // bulk one byte short of declared length (+ trailing CRLF)
    Buffer.from(`$5${CRLF}hello`), // bulk body present but trailing CRLF missing
    Buffer.from(`*2${CRLF}:1${CRLF}`), // array missing its second element
    Buffer.from(`>2${CRLF}:1${CRLF}`), // push missing its second element
    Buffer.from(":"), // integer header with no CRLF yet
  ])("truncation raises IncompleteFrame: %j", (partial) => {
    // Each kind of partial buffer raises IncompleteFrame.
    expect(() => decodeResp(partial)).toThrow(IncompleteFrame);
  });

  it.each([
    Buffer.from(`$5${CRLF}helloXX`), // body is followed by a non-CRLF terminator
    Buffer.from(`$-2${CRLF}`), // only -1 is a valid null bulk length
    Buffer.from(`*-2${CRLF}`), // only -1 is a valid null array length
  ])("malformed framing raises PhotonProtocolError: %j", (malformed) => {
    expect(() => decodeResp(malformed)).toThrow(PhotonProtocolError);
  });

  it.each([
    Buffer.from(`!bad${CRLF}`), // unknown prefix
    Buffer.from(`:notanint${CRLF}`), // integer header unparseable
    Buffer.from(`$notanint${CRLF}`), // bulk length unparseable
    Buffer.from(`*notanint${CRLF}`), // array count unparseable
    Buffer.concat([Buffer.from([0x3a, 0xb1]), Buffer.from(CRLF)]), // non-ASCII integer header
  ])("decode garbage raises PhotonProtocolError: %j", (garbage) => {
    // Genuinely corrupt input raises PhotonProtocolError.
    expect(() => decodeResp(garbage)).toThrow(PhotonProtocolError);
  });
});
