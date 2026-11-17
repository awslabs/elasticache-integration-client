// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Unit tests for the contract logic in mock-cache.ts.
 */
import { describe, expect, it } from "vitest";

import { decodeResp, RespError } from "../src/resp.js";
import { MockCache } from "./mock-cache.js";

describe("MockCache contract", () => {
  it("config with no args replies null", () => {
    /**
     * A no-argument aws.config ("overrides unchanged") is valid: the default mock replies with
     * the RESP3 null (nothing changed), and records the cycle as null in configOverrides.
     */
    const cache = new MockCache();

    const frame = cache.handle([Buffer.from("aws.config")]);

    expect(frame).toEqual(Buffer.from("_\r\n"));
    expect(decodeResp(frame)[0]).toBeNull();
    expect(cache.configOverrides).toEqual([null]);
  });

  it("config with no args can reply a resolved config", () => {
    /**
     * With noArgConfigReply set, a no-argument aws.config replies with it instead of null,
     * simulating the cache changing its own settings independently of the client.
     */
    const changed = { version: "1.0", refresh_interval: 30, predicates: [] };
    const cache = new MockCache({ noArgConfigReply: changed });

    const [value] = decodeResp(cache.handle([Buffer.from("aws.config")]));

    expect(JSON.parse(String(value))).toEqual(changed);
  });

  it("config with wrong arg count is rejected", () => {
    /**
     * aws.config with 2+ args is rejected by the mock with a CONFIG_INVALID error (0 args is
     * the valid no-argument form; 1 arg is the valid explicit-overrides form).
     */
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("aws.config"), Buffer.from("{}"), Buffer.from("x")]),
    );

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "CONFIG_INVALID" });
  });

  it("config with overrides serves the config and records them", () => {
    const cache = new MockCache();

    const [value] = decodeResp(cache.handle([Buffer.from("aws.config"), Buffer.from('{"a":1}')]));

    expect(JSON.parse(String(value))).toMatchObject({ refresh_interval: 60 });
    expect(cache.configOverrides).toEqual([{ a: 1 }]);
  });

  it("config with non-JSON overrides is rejected", () => {
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("aws.config"), Buffer.from("not json")]),
    );

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "CONFIG_INVALID" });
  });

  it("request with too few args is rejected", () => {
    /** aws.request with fewer than 5 args is rejected by the mock. */
    const [value] = decodeResp(
      new MockCache().handle([Buffer.from("aws.request"), Buffer.from("POST")]),
    );

    expect(value).toBeInstanceOf(RespError);
    expect(value).toMatchObject({ code: "ERR" });
  });
});
