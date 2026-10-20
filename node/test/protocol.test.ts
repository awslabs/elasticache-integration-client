import { describe, expect, it } from "vitest";

import {
  ElastiCacheIntegrationsError,
  ElastiCacheIntegrationsOverridesRejected,
  ElastiCacheIntegrationsProtocolError,
  ElastiCacheIntegrationsRejectedError,
} from "../src/errors.js";
import {
  ElastiCacheIntegrationsRequest,
  ElastiCacheIntegrationsResponse,
  RequestMetadata,
  ResolvedConfig,
} from "../src/models.js";
import {
  CONFIG_COMMAND,
  REQUEST_COMMAND,
  deserializeResponse,
  formatMetadata,
  normalizeHeaders,
  parseConfigResponse,
  parseRequestResponse,
  PROTOCOL_VERSION,
  serializeConfig,
  serializeRequest,
} from "../src/protocol.js";
import { RespError } from "../src/resp.js";

describe("ElastiCacheIntegrations protocol", () => {
  it("serializes an empty config override compactly", () => {
    expect(serializeConfig({})).toEqual([
      CONFIG_COMMAND,
      [Buffer.from(`{"version":"${PROTOCOL_VERSION}"}`)],
    ]);
  });

  it.each([null, undefined])("sends no argument for %s overrides", (overrides) => {
    // No overrides means "give me the current config", which is a zero-argument command.
    expect(serializeConfig(overrides)).toEqual([CONFIG_COMMAND, []]);
  });

  it("lets a caller override the protocol version it sends", () => {
    // The injected version is a default, not a floor: this is how a test drives a
    // version the client would not otherwise send.
    const [, [payload]] = serializeConfig({ version: "9.9" });

    expect(JSON.parse(payload.toString())).toEqual({ version: "9.9" });
  });

  it("serializes config overrides without whitespace", () => {
    const [, [payload]] = serializeConfig({ b: 2, a: 1 });

    expect(payload.toString()).not.toContain(" ");
    expect(JSON.parse(payload.toString())).toEqual({
      version: PROTOCOL_VERSION,
      b: 2,
      a: 1,
    });
  });

  it("parses a JSON config bulk string and preserves the wire object", () => {
    // The reply carries routing only — version, refresh_interval, predicates — so the
    // extra field here stands for one a later server adds, not a lifetime: no setting
    // is ever echoed back.
    const config = parseConfigResponse(
      Buffer.from(
        '{"version":"1.0","refresh_interval":60,"a_field_this_client_does_not_model":1,"predicates":[]}',
      ),
    );

    expect(config).toBeInstanceOf(ResolvedConfig);
    expect(config.version).toBe("1.0");
    expect(config.refreshInterval).toBe(60);
    expect(config.raw).toEqual({
      version: "1.0",
      refresh_interval: 60,
      a_field_this_client_does_not_model: 1,
      predicates: [],
    });
  });

  it("defaults a missing version while preserving raw data", () => {
    const config = parseConfigResponse(Buffer.from('{"refresh_interval":60,"predicates":[]}'));

    expect(config.version).toBeUndefined();
    expect(config.refreshInterval).toBe(60);
    expect(config.raw).toEqual({ refresh_interval: 60, predicates: [] });
  });

  // Python rejects 56.0. Delete this test when the shared vector
  // config_integral_float_refresh_interval leaves KNOWN_GAPS in protocol-vectors.test.ts.
  it("accepts integer-valued JSON numbers such as 56.0", () => {
    const config = parseConfigResponse(Buffer.from('{"refresh_interval":56.0,"predicates":[]}'));

    expect(config.refreshInterval).toBe(56);
  });

  it.each([1, 5, 3600, 3601])("accepts the positive refresh_interval %s", (refreshInterval) => {
    const config = parseConfigResponse(
      Buffer.from(`{"refresh_interval":${refreshInterval},"predicates":[]}`),
    );

    expect(config.refreshInterval).toBe(refreshInterval);
  });

  it.each([
    // Missing entirely; the refresher has no interval to schedule from.
    '{"predicates":[]}',
    // Explicit null.
    '{"refresh_interval":null,"predicates":[]}',
    // Wrong type: string.
    '{"refresh_interval":"60","predicates":[]}',
    // Wrong type: boolean.
    '{"refresh_interval":true,"predicates":[]}',
    // Wrong type: object.
    '{"refresh_interval":{},"predicates":[]}',
    // Fractional.
    '{"refresh_interval":60.5,"predicates":[]}',
    // Not positive.
    '{"refresh_interval":0,"predicates":[]}',
    '{"refresh_interval":-1,"predicates":[]}',
  ])("rejects the invalid refresh_interval in %s", (body) => {
    // Invalid intervals must fail at parse time, not crash a background refresher later.
    expect(() => parseConfigResponse(Buffer.from(body))).toThrow(ElastiCacheIntegrationsError);
  });

  it("requires request metadata at the model boundary", () => {
    expect(() => new RequestMetadata(undefined as never)).toThrow(TypeError);
    expect(() => new RequestMetadata({} as never)).toThrow(TypeError);
    expect(
      () => new ElastiCacheIntegrationsRequest({ method: "POST", url: "https://x/" } as never),
    ).toThrow(TypeError);
    expect(
      () =>
        new ElastiCacheIntegrationsRequest({
          method: "POST",
          url: "https://x/",
          metadata: {} as never,
        }),
    ).toThrow(TypeError);
  });

  it.each([
    // A RespError config reply must be rejected, not parsed.
    new RespError("ERR", "nope"),
    // A RESP array shape for config is intentionally unsupported.
    [Buffer.from("version"), Buffer.from("1.0")],
    // A non-JSON bulk string must be rejected.
    Buffer.from("not json"),
    // Valid JSON that is not an object (for example, an array) must be rejected.
    Buffer.from("[1,2,3]"),
  ])("rejects malformed config response %#", (value) => {
    expect(() => parseConfigResponse(value as never)).toThrow(ElastiCacheIntegrationsError);
  });

  it("maps a CONFIG_INVALID reply to ElastiCacheIntegrationsOverridesRejected", () => {
    expect(() =>
      parseConfigResponse(new RespError("CONFIG_INVALID", "table does not exist")),
    ).toThrow(ElastiCacheIntegrationsOverridesRejected);
    expect(() => parseConfigResponse(new RespError("ERR", "nope"))).not.toThrow(
      ElastiCacheIntegrationsOverridesRejected,
    );
  });

  it("serializes a request into the five protocol arguments", () => {
    const request = new ElastiCacheIntegrationsRequest({
      method: "POST",
      url: "https://dynamodb.us-east-1.amazonaws.com/",
      headers: { "Content-Type": "application/x-amz-json-1.0" },
      body: Buffer.from("{}"),
      metadata: new RequestMetadata({ service: "dynamodb", operation: "GetItem" }),
    });

    const [command, args] = serializeRequest(request);

    // The args are exactly the 5 request args and the command name is correct.
    expect(command).toBe(REQUEST_COMMAND);
    expect(command).toBe("aws.request");
    expect(args).toHaveLength(5);
    expect(args).toEqual([
      Buffer.from("POST"),
      Buffer.from("https://dynamodb.us-east-1.amazonaws.com/"),
      Buffer.from("content-type:application/x-amz-json-1.0"),
      Buffer.from("{}"),
      Buffer.from("service=dynamodb&op=GetItem"),
    ]);
  });

  it("lowercases the service so it matches the proxy's predicates", () => {
    const request = new ElastiCacheIntegrationsRequest({
      method: "POST",
      url: "https://x/",
      metadata: new RequestMetadata({ service: "DynamoDB", operation: "GetItem" }),
    });

    const [, args] = serializeRequest(request);

    expect(args[4]).toEqual(Buffer.from("service=dynamodb&op=GetItem"));
  });

  it("converts string bodies during serialization", () => {
    const request = new ElastiCacheIntegrationsRequest({
      method: "POST",
      url: "https://x/",
      body: "hello",
      metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
    });

    expect(serializeRequest(request)[1][3]).toEqual(Buffer.from("hello"));
  });

  it("normalizes headers deterministically and excludes volatile auth headers", () => {
    const headersA = {
      "Content-Type": "application/json",
      "X-Amz-Target": "DynamoDB_20120810.GetItem",
      Authorization: "AWS4-HMAC-SHA256 ...",
      "X-Amz-Date": "20260624T000000Z",
      "x-amz-security-token": "tok",
      "x-amz-request-id": "rid",
      "amz-sdk-request": "attempt=1",
    };
    const headersB = {
      "x-amz-target": "  DynamoDB_20120810.GetItem  ",
      "CONTENT-TYPE": "application/json",
      authorization: "different-sig",
    };

    expect(normalizeHeaders(headersA)).toEqual(normalizeHeaders(headersB));
    expect(normalizeHeaders(headersA)).toEqual(
      Buffer.from("content-type:application/json\nx-amz-target:DynamoDB_20120810.GetItem"),
    );
  });

  it("returns empty normalized headers for empty or excluded input", () => {
    expect(normalizeHeaders({})).toEqual(Buffer.alloc(0));
    expect(normalizeHeaders(null)).toEqual(Buffer.alloc(0));
    expect(normalizeHeaders({ Authorization: "x", "X-Amz-Date": "y" })).toEqual(Buffer.alloc(0));
  });

  it("retains x-amz-content-sha256 in normalized headers", () => {
    expect(normalizeHeaders({ "x-amz-content-sha256": "hash" })).toEqual(
      Buffer.from("x-amz-content-sha256:hash"),
    );
  });

  it("sorts duplicate normalized names by value like Python", () => {
    expect(normalizeHeaders({ "X-Key": "z", " x-key ": "a" })).toEqual(
      Buffer.from("x-key:a\nx-key:z"),
    );
  });

  it.each([
    ["dynamodb", "GetItem", "service=dynamodb&op=GetItem"],
    ["s3", "GetObject", "service=s3&op=GetObject"],
    [undefined, undefined, "service=&op="],
    ["dynamodb", undefined, "service=dynamodb&op="],
    [undefined, "GetItem", "service=&op=GetItem"],
  ] as const)("formats metadata", (service, operation, expected) => {
    expect(formatMetadata(service, operation)).toEqual(Buffer.from(expected));
  });

  it("rejects invalid UTF-8 in response headers", () => {
    expect(() =>
      deserializeResponse([200, Buffer.from([0xff, 0x3a, 0x78]), Buffer.alloc(0)]),
    ).toThrow(ElastiCacheIntegrationsError);
  });

  it("rejects invalid UTF-8 in config responses", () => {
    expect(() => parseConfigResponse(Buffer.from([0xff]))).toThrow(ElastiCacheIntegrationsError);
  });

  it("escapes non-ASCII config values like Python json.dumps", () => {
    expect(serializeConfig({ text: "café", emoji: "😀" })[1][0]).toEqual(
      Buffer.from(`{"version":"${PROTOCOL_VERSION}","text":"caf\\u00e9","emoji":"\\ud83d\\ude00"}`),
    );
  });

  it("rejects non-finite config numbers instead of silently sending null", () => {
    expect(() => serializeConfig({ nan: Number.NaN })).toThrow(ElastiCacheIntegrationsError);
    expect(() => serializeConfig({ infinity: Number.POSITIVE_INFINITY })).toThrow(
      ElastiCacheIntegrationsError,
    );
  });

  it("rejects non-byte response headers and bodies", () => {
    for (const value of [
      [200, null, null],
      [200, "content-type:application/json", Buffer.alloc(0)],
      [200, Buffer.alloc(0), "body"],
    ]) {
      expect(() => deserializeResponse(value)).toThrow(ElastiCacheIntegrationsError);
    }
  });

  it("deserializes a basic response with buffer fields", () => {
    const response = deserializeResponse([
      200,
      Buffer.from("content-type:application/json"),
      Buffer.from('{"k":1}'),
    ]);

    expect(response).toBeInstanceOf(ElastiCacheIntegrationsResponse);
    expect(response.statusCode).toBe(200);
    expect(response.headers).toEqual({ "content-type": "application/json" });
    expect(response.body).toEqual(Buffer.from('{"k":1}'));
  });

  it("deserializes empty headers and body", () => {
    const response = deserializeResponse([204, Buffer.alloc(0), Buffer.alloc(0)]);

    expect(response.statusCode).toBe(204);
    expect(response.headers).toEqual({});
    expect(response.body).toEqual(Buffer.alloc(0));
  });

  it("skips malformed response header lines", () => {
    expect(
      deserializeResponse([
        200,
        Buffer.from("content-type:application/json\ngarbage"),
        Buffer.alloc(0),
      ]).headers,
    ).toEqual({ "content-type": "application/json" });
  });

  it("trims response header names and values", () => {
    expect(
      deserializeResponse([200, Buffer.from(" content-type : application/json "), Buffer.alloc(0)])
        .headers,
    ).toEqual({ "content-type": "application/json" });
  });

  it.each([99, 600, 0, -1, 1000])("rejects status %s outside HTTP range", (status) => {
    expect(() => deserializeResponse([status, "", ""])).toThrow(ElastiCacheIntegrationsError);
  });

  it("rejects non-decimal statuses and malformed response shapes", () => {
    expect(() => deserializeResponse([Buffer.from("not-a-number"), "", ""])).toThrow(
      ElastiCacheIntegrationsError,
    );
    expect(() => deserializeResponse(["200.0", "", ""])).toThrow(ElastiCacheIntegrationsError);
    expect(() => deserializeResponse(["0xC8", "", ""])).toThrow(ElastiCacheIntegrationsError);
    for (const value of [null, undefined, [], [200], [200, ""], [200, "", "", "x"]]) {
      expect(() => deserializeResponse(value)).toThrow(ElastiCacheIntegrationsError);
    }
  });

  it("rejects request response error frames", () => {
    // A RespError request reply raises rather than being returned as a response.
    let failure: unknown;
    try {
      parseRequestResponse(new RespError("ORIGIN_ERROR", "boom"));
    } catch (error) {
      failure = error;
    }
    expect(failure).toBeInstanceOf(ElastiCacheIntegrationsRejectedError);
    expect((failure as ElastiCacheIntegrationsRejectedError).code).toBe("ORIGIN_ERROR");
  });

  it("rejects non-array request responses", () => {
    // A non-array request reply, such as a bare bulk string, is malformed.
    expect(() => parseRequestResponse(Buffer.from("not-an-array"))).toThrow(
      ElastiCacheIntegrationsProtocolError,
    );
  });

  it("rejects unsupported request body types", () => {
    expect(() =>
      serializeRequest(
        new ElastiCacheIntegrationsRequest({
          method: "POST",
          url: "https://x/",
          body: 12345 as never,
          metadata: new RequestMetadata({ service: "svc", operation: "Test" }),
        }),
      ),
    ).toThrow(ElastiCacheIntegrationsError);
  });
});
