import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import {
  PhotonError,
  PhotonOverridesRejected,
  PhotonProtocolError,
  PhotonRejectedError,
} from "../src/errors.js";
import { PhotonRequest, RequestMetadata } from "../src/models.js";
import {
  parseConfigResponse,
  parseRequestResponse,
  serializeConfig,
  serializeRequest,
} from "../src/protocol.js";
import { decodeResp, encodeResp } from "../src/resp.js";

// Cross-client wire vectors. Python and Java run the same file, so a client that drifts from the
// shared bytes fails its own gate.
interface EncodeVector {
  name: string;
  request?: {
    method: string;
    url: string;
    headers: Record<string, string>;
    body: string;
    metadata: { service: string; operation: string };
  };
  overrides?: Record<string, unknown> | null;
  frame: string;
}

interface DecodeVector {
  name: string;
  command: "photon.request" | "photon.config";
  reply: string;
  response?: { status: number; headers: Record<string, string>; body: string };
  config?: { version: string; refresh_interval: number };
  error?: keyof typeof ERRORS;
  code?: string;
}

const VECTORS = JSON.parse(
  readFileSync(new URL("../../shared/protocol-vectors.json", import.meta.url), "utf8"),
) as { encode: EncodeVector[]; decode: DecodeVector[] };

const ERRORS = {
  protocol: PhotonProtocolError,
  rejected: PhotonRejectedError,
  overrides_rejected: PhotonOverridesRejected,
  error: PhotonError,
};

/**
 * Vectors Node does not match yet. Each one must still fail (`it.fails`); when a fix makes it pass,
 * the test fails until the name is removed here.
 */
const KNOWN_GAPS = new Set([
  // parseRefreshInterval checks Number.isInteger, and JSON.parse reads 60.0 as 60.
  "config_integral_float_refresh_interval",
]);

function assertEncodes(vector: EncodeVector): void {
  const [command, args] = vector.request
    ? serializeRequest(
        new PhotonRequest({
          method: vector.request.method,
          url: vector.request.url,
          headers: vector.request.headers,
          body: Buffer.from(vector.request.body, "utf8"),
          metadata: new RequestMetadata(vector.request.metadata),
        }),
      )
    : serializeConfig(vector.overrides);

  expect(encodeResp(command, ...args).toString("utf8")).toBe(vector.frame);
}

function assertDecodes(vector: DecodeVector): void {
  const replyBytes = Buffer.from(vector.reply, "utf8");
  const [reply, consumed] = decodeResp(replyBytes);
  expect(consumed).toBe(replyBytes.length);
  const parse = vector.command === "photon.request" ? parseRequestResponse : parseConfigResponse;

  if (vector.error) {
    let thrown: unknown;
    try {
      parse(reply);
    } catch (error) {
      thrown = error;
    }
    expect(thrown).toBeInstanceOf(PhotonError);
    // "error" accepts any PhotonError; the other kinds name an exact class.
    if (vector.error !== "error") {
      expect((thrown as Error).constructor).toBe(ERRORS[vector.error]);
    }
    if (vector.code) {
      expect((thrown as PhotonRejectedError).code).toBe(vector.code);
    }
  } else if (vector.response) {
    const response = parseRequestResponse(reply);
    expect(response.statusCode).toBe(vector.response.status);
    expect(response.headers).toEqual(vector.response.headers);
    expect(response.body.toString("utf8")).toBe(vector.response.body);
  } else {
    const config = parseConfigResponse(reply);
    expect(config.version).toBe(vector.config?.version);
    expect(config.refreshInterval).toBe(vector.config?.refresh_interval);
  }
}

describe("shared protocol vectors", () => {
  for (const vector of VECTORS.encode) {
    const test = KNOWN_GAPS.has(vector.name) ? it.fails : it;
    test(`encodes ${vector.name}`, () => assertEncodes(vector));
  }

  for (const vector of VECTORS.decode) {
    const test = KNOWN_GAPS.has(vector.name) ? it.fails : it;
    test(`decodes ${vector.name}`, () => assertDecodes(vector));
  }

  // This runner skips any field it doesn't know, so an unknown or missing field fails here.
  it("uses only known fields", () => {
    const keys = (value: object) => Object.keys(value).sort().join(",");
    expect(keys(VECTORS)).toBe("decode,description,encode");
    for (const vector of VECTORS.encode) {
      expect(["frame,name,request", "frame,name,overrides"]).toContain(keys(vector));
      if (vector.request) {
        expect(keys(vector.request)).toBe("body,headers,metadata,method,url");
        expect(keys(vector.request.metadata)).toBe("operation,service");
      }
    }
    for (const vector of VECTORS.decode) {
      const outcome = Object.keys(vector)
        .filter((key) => !["name", "command", "reply"].includes(key))
        .sort()
        .join(",");
      expect(["response", "config", "error", "code,error"]).toContain(outcome);
      if (vector.response) expect(keys(vector.response)).toBe("body,headers,status");
      if (vector.config) expect(keys(vector.config)).toBe("refresh_interval,version");
    }
  });

  it("lists only existing vectors as known gaps", () => {
    const names = new Set([...VECTORS.encode, ...VECTORS.decode].map((vector) => vector.name));
    expect([...KNOWN_GAPS].filter((name) => !names.has(name))).toEqual([]);
  });
});
