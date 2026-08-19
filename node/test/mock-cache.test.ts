/**
 * Unit tests for the contract logic in mock-cache.ts.
 */
import { describe, expect, it } from "vitest";

import { decodeResp, RespError } from "../src/resp.js";
import { MockCache } from "./mock-cache.js";

const _REQUEST = [
  Buffer.from("photon.request"),
  Buffer.from("POST"),
  Buffer.from("https://x/"),
  Buffer.from("hdrs"),
  Buffer.from("body"),
  Buffer.from("service=s&op=o"),
];

describe("MockCache contract", () => {
  it("config with wrong arg count is rejected", () => {
    /** photon.config with the wrong number of args is rejected by the mock with a CONFIG_INVALID error. */
    const [value] = decodeResp(new MockCache().handle([Buffer.from("photon.config")]));

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "CONFIG_INVALID" });
  });

  it("request with too few args is rejected", () => {
    /** photon.request with fewer than 5 args is rejected by the mock. */
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("photon.request"), Buffer.from("POST")]),
    );

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "ERR" });
  });

  it("request parses optional TTL and NTTL", () => {
    /** The mock parses optional TTL/NTTL keyword args off a request into the recorded record. */
    const cache = new MockCache();

    cache.handle([
      ..._REQUEST,
      Buffer.from("TTL"),
      Buffer.from("300"),
      Buffer.from("NTTL"),
      Buffer.from("30"),
    ]);

    expect(cache.lastRequest).toMatchObject({ ttl: 300, nttl: 30 });
  });
});
