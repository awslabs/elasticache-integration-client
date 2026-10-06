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
  it("config with no args replies null", () => {
    /**
     * A no-argument photon.config ("overrides unchanged") is valid: the default mock replies with
     * the RESP3 null (nothing changed), and records the cycle as null in configOverrides.
     */
    const cache = new MockCache();

    const frame = cache.handle([Buffer.from("photon.config")]);

    expect(frame).toEqual(Buffer.from("_\r\n"));
    expect(decodeResp(frame)[0]).toBeNull();
    expect(cache.configOverrides).toEqual([null]);
  });

  it("config with no args can reply a resolved config", () => {
    /**
     * With noArgConfigReply set, a no-argument photon.config replies with it instead of null,
     * simulating the cache changing its own settings independently of the client.
     */
    const changed = { version: "v1", refresh_interval: 30, predicates: [] };
    const cache = new MockCache({ noArgConfigReply: changed });

    const [value] = decodeResp(cache.handle([Buffer.from("photon.config")]));

    expect(JSON.parse(String(value))).toEqual(changed);
  });

  it("config with wrong arg count is rejected", () => {
    /**
     * photon.config with 2+ args is rejected by the mock with a CONFIG_INVALID error (0 args is
     * the valid no-argument form; 1 arg is the valid explicit-overrides form).
     */
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("photon.config"), Buffer.from("{}"), Buffer.from("x")]),
    );

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "CONFIG_INVALID" });
  });

  it("config with overrides serves the config and records them", () => {
    const cache = new MockCache();

    const [value] = decodeResp(
      cache.handle([Buffer.from("photon.config"), Buffer.from('{"a":1}')]),
    );

    expect(JSON.parse(String(value))).toMatchObject({ refresh_interval: 60 });
    expect(cache.configOverrides).toEqual([{ a: 1 }]);
  });

  it("config with non-JSON overrides is rejected", () => {
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("photon.config"), Buffer.from("not json")]),
    );

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
