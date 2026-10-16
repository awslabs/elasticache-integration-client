/** Tests for the standalone RESP3 wire codec. */
import { describe, expect, it } from "vitest";

import {
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsProtocolError,
} from "../src/errors.js";
import {
  decodeResp,
  encodeResp,
  IncompleteFrame,
  RespError,
  RespPush,
  type RespValue,
} from "../src/resp.js";

const CRLF = "\r\n";

describe("RESP codec", () => {
  it("incomplete frame is not a ElastiCacheIntegrationsError", () => {
    // IncompleteFrame ("need more bytes") must be a plain Error, not a ElastiCacheIntegrationsError,
    // so an incremental reader can distinguish truncation from corruption.
    const error = new IncompleteFrame("partial");

    expect(error).toBeInstanceOf(Error);
    expect(error).not.toBeInstanceOf(ElastiCacheIntegrationsError);
  });

  it("encode/decode round trip", () => {
    // Encoding a command then decoding it yields the original elements and
    // consumes the whole buffer.
    const encoded = encodeResp("aws.request", Buffer.from("POST"), Buffer.from("body"));

    expect(decodeResp(encoded)).toEqual([
      [Buffer.from("aws.request"), Buffer.from("POST"), Buffer.from("body")],
      encoded.length,
    ]);
  });

  it("encode command RESP3 array framing", () => {
    // Verifies the exact RESP3 byte framing (*N, $len, CRLFs) produced by the encoder.
    expect(encodeResp("aws.request", Buffer.from("POST"), Buffer.from("https://x/"))).toEqual(
      Buffer.from(
        `*3${CRLF}$11${CRLF}aws.request${CRLF}$4${CRLF}POST${CRLF}$10${CRLF}https://x/${CRLF}`,
      ),
    );
  });

  it("encode command rejects non-buffer arg", () => {
    // Passing a string argument (programmer misuse) is rejected.
    expect(() => encodeResp("aws.request", "not-bytes" as never)).toThrow(
      ElastiCacheIntegrationsError,
    );
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

  it("rejects a simple string that is not valid UTF-8", () => {
    // Replacing invalid bytes would silently corrupt the value, so it is a protocol error.
    const frame = Buffer.concat([Buffer.from("+"), Buffer.from([0xff]), Buffer.from(CRLF)]);
    expect(() => decodeResp(frame)).toThrow(ElastiCacheIntegrationsProtocolError);
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

  it("decodes a aws.response push with a decimal request id", () => {
    const frame = Buffer.from(
      `>3${CRLF}$12${CRLF}aws.response${CRLF}:42${CRLF}*1${CRLF}:200${CRLF}`,
    );

    const [push, consumed] = decodeResp(frame);

    expect(push).toEqual(new RespPush([Buffer.from("aws.response"), 42, [200]]));
    expect(consumed).toBe(frame.length);
  });

  it("decodes a null array", () => {
    // Decodes a *-1 null array to null.
    expect(decodeResp(Buffer.from(`*-1${CRLF}`))).toEqual([null, 5]);
  });

  it("decodes a RESP3 map", () => {
    const frame = Buffer.from(
      `%2${CRLF}$5${CRLF}proto${CRLF}:3${CRLF}$6${CRLF}server${CRLF}$6${CRLF}valkey${CRLF}`,
    );

    expect(decodeResp(frame)).toEqual([
      new Map<unknown, unknown>([
        [Buffer.from("proto"), 3],
        [Buffer.from("server"), Buffer.from("valkey")],
      ]),
      frame.length,
    ]);
  });

  it("decodes an empty RESP3 map", () => {
    expect(decodeResp(Buffer.from(`%0${CRLF}`))).toEqual([new Map(), 4]);
  });

  it("preserves aggregate RESP3 map keys", () => {
    const [value] = decodeResp(Buffer.from(`%1${CRLF}*1${CRLF}:1${CRLF}:2${CRLF}`));

    expect(value).toEqual(new Map([[[1], 2]]));
  });

  it.each([
    Buffer.from(""), // empty buffer
    Buffer.from("+missing-crlf"), // header with no terminating CRLF
    Buffer.from(`$5${CRLF}hell`), // bulk one byte short of declared length (+ trailing CRLF)
    Buffer.from(`$5${CRLF}hello`), // bulk body present but trailing CRLF missing
    Buffer.from(`*2${CRLF}:1${CRLF}`), // array missing its second element
    Buffer.from(`%1${CRLF}$5${CRLF}proto${CRLF}`), // map key missing its value
    Buffer.from(`>2${CRLF}:1${CRLF}`), // push missing its second element
    Buffer.from(":"), // integer header with no CRLF yet
  ])("truncation raises IncompleteFrame: %j", (partial) => {
    // Each kind of partial buffer raises IncompleteFrame.
    expect(() => decodeResp(partial)).toThrow(IncompleteFrame);
  });

  it("decodes a RESP3 null", () => {
    // Decodes the _ null frame to null.
    expect(decodeResp(Buffer.from(`_${CRLF}`))).toEqual([null, 3]);
  });

  it("decodes a RESP3 null inside an array", () => {
    expect(decodeResp(Buffer.from(`*2${CRLF}_${CRLF}:1${CRLF}`))).toEqual([[null, 1], 11]);
  });

  it("rejects a RESP3 null with a non-empty header", () => {
    expect(() => decodeResp(Buffer.from(`_unexpected${CRLF}`))).toThrow(
      ElastiCacheIntegrationsProtocolError,
    );
  });

  it.each([
    Buffer.from(`$5${CRLF}helloXX`), // body is followed by a non-CRLF terminator
    Buffer.from(`$-2${CRLF}`), // only -1 is a valid null bulk length
    Buffer.from(`*-2${CRLF}`), // only -1 is a valid null array length
    Buffer.from(`%-1${CRLF}`), // RESP3 has no null map
  ])("malformed framing raises ElastiCacheIntegrationsProtocolError: %j", (malformed) => {
    expect(() => decodeResp(malformed)).toThrow(ElastiCacheIntegrationsProtocolError);
  });

  it.each([
    Buffer.from(`!bad${CRLF}`), // unknown prefix
    Buffer.from(`:notanint${CRLF}`), // integer header unparseable
    Buffer.from(`$notanint${CRLF}`), // bulk length unparseable
    Buffer.from(`*notanint${CRLF}`), // array count unparseable
    Buffer.concat([Buffer.from([0x3a, 0xb1]), Buffer.from(CRLF)]), // non-ASCII integer header
  ])("decode garbage raises ElastiCacheIntegrationsProtocolError: %j", (garbage) => {
    // Genuinely corrupt input raises ElastiCacheIntegrationsProtocolError.
    expect(() => decodeResp(garbage)).toThrow(ElastiCacheIntegrationsProtocolError);
  });

  describe("nesting depth", () => {
    const NESTING_MESSAGE = "RESP nesting exceeds 100 levels";

    /** `levels` aggregates of the given prefix around a single `:1` integer. */
    function nested(prefix: "*" | "%" | ">", levels: number): Buffer {
      const unit = {
        "*": `*1${CRLF}`,
        "%": `%1${CRLF}:0${CRLF}`,
        ">": `>2${CRLF}+kind${CRLF}`,
      }[prefix];
      return Buffer.from(`${unit.repeat(levels)}:1${CRLF}`);
    }

    /** Walk down `levels` aggregates to the innermost value. */
    function unwrap(value: RespValue, prefix: "*" | "%" | ">", levels: number): RespValue {
      let current = value;
      for (let level = 0; level < levels; level += 1) {
        if (prefix === "*") {
          current = (current as RespValue[])[0]!;
        } else if (prefix === "%") {
          current = [...(current as Map<RespValue, RespValue>).values()][0]!;
        } else {
          current = (current as RespPush).items[1]!;
        }
      }
      return current;
    }

    it.each(["*", "%", ">"] as const)(
      "decode nesting past the cap raises protocol error: %s",
      (prefix) => {
        const frame = nested(prefix, 101);

        expect(() => decodeResp(frame)).toThrow(ElastiCacheIntegrationsProtocolError);
        expect(() => decodeResp(frame)).toThrow(NESTING_MESSAGE);
      },
    );

    it.each(["*", "%", ">"] as const)("decode nesting at the cap decodes: %s", (prefix) => {
      const frame = nested(prefix, 100);

      const [value, consumed] = decodeResp(frame);

      expect(consumed).toBe(frame.length);
      expect(unwrap(value, prefix, 100)).toBe(1);
    });

    it("decode nesting past the cap raises far past the stack limit", () => {
      const frame = nested("*", 5000);

      expect(() => decodeResp(frame)).toThrow(ElastiCacheIntegrationsProtocolError);
      expect(() => decodeResp(frame)).toThrow(NESTING_MESSAGE);
    });

    it("decode truncated nesting under the cap raises IncompleteFrame", () => {
      expect(() => decodeResp(Buffer.from(`*1${CRLF}`.repeat(50)))).toThrow(IncompleteFrame);
    });
  });
});
