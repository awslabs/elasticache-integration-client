import { readFileSync } from "node:fs";

import * as dynamodb from "@aws-sdk/client-dynamodb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { client } from "../src/index.js";
import { MockCache } from "./mock-cache.js";

// Cross-client SDK request cases. Each case goes through the real AWS SDK into the mock cache.
// Python and Java run the same file through their own SDKs, so a client whose adapter builds a
// different request fails its own gate.
interface SdkRequestCase {
  name: string;
  service: string;
  operation: string;
  params: Record<string, unknown>;
  expected: {
    method: string;
    url: string;
    metadata: string;
    headers: Record<string, string | null>;
  };
}

// Header names the JS SDK sends or omits compared with expected.headers, which lists what Python
// sends. The header-name check is exact, so a name missing from this list fails, and so does an
// entry the SDK no longer adds or omits.
const ACCEPTED_HEADER_DIFFERENCES = {
  extra: ["host", "x-amz-content-sha256", "x-amz-user-agent"],
  missing: [] as string[],
};

const CASES = JSON.parse(
  readFileSync(new URL("../../shared/sdk-request-cases.json", import.meta.url), "utf8"),
) as { config: Record<string, unknown>; excluded_headers: string[]; cases: SdkRequestCase[] };

type Command = new (
  input: Record<string, unknown>,
) => Parameters<dynamodb.DynamoDBClient["send"]>[0];

function commandFor(operation: string): Command {
  const command = (dynamodb as unknown as Record<string, Command | undefined>)[
    `${operation}Command`
  ];
  if (!command) {
    throw new Error(`@aws-sdk/client-dynamodb has no ${operation}Command`);
  }
  return command;
}

describe("shared SDK request cases", () => {
  let cache: MockCache;

  beforeEach(async () => {
    cache = new MockCache({ config: CASES.config, responseBody: Buffer.from("{}") });
    await cache.start();
  });

  afterEach(async () => {
    await cache.stop();
  });

  for (const testCase of CASES.cases) {
    it(`sends ${testCase.name}`, async () => {
      const sdkClient = await client(testCase.service, dynamodb.DynamoDBClient, {
        endpoint: cache.endpoint,
        cacheName: "test-cache",
        region: "us-east-1",
        connectorOptions: { tls: false },
        clientConfig: {
          // A session token makes the SDK send x-amz-security-token, which ElastiCacheIntegrations must strip.
          credentials: {
            accessKeyId: "testkey",
            secretAccessKey: "testsecret",
            sessionToken: "testtoken",
          },
          // ElastiCacheIntegrations must carry every case; a request that falls back to the origin fails here
          // instead of reaching real AWS.
          requestHandler: {
            handle: (request: { hostname?: string }) => {
              throw new Error(`request went to the origin: ${request.hostname}`);
            },
          },
        },
      });
      try {
        const Command = commandFor(testCase.operation);
        await sdkClient.send(new Command(testCase.params));
      } finally {
        sdkClient.destroy();
      }

      expect(cache.requests).toHaveLength(1);
      const request = cache.requests[0]!;
      const expected = testCase.expected;
      expect(request.method.toString("utf8")).toBe(expected.method);
      expect(request.url.toString("utf8")).toBe(expected.url);
      expect(request.metadata.toString("utf8")).toBe(expected.metadata);
      expect(JSON.parse(request.body.toString("utf8"))).toEqual(testCase.params);
      // Normalized headers are one "name:value" line each, so compare whole lines.
      const lines = request.headers.toString("utf8").split("\n");
      const names = lines.map((line) => line.slice(0, line.indexOf(":"))).sort();
      const expectedNames = Object.keys(expected.headers)
        .filter((name) => !ACCEPTED_HEADER_DIFFERENCES.missing.includes(name))
        .concat(ACCEPTED_HEADER_DIFFERENCES.extra)
        .sort();
      expect(names).toEqual(expectedNames);
      for (const [name, value] of Object.entries(expected.headers)) {
        if (value !== null) {
          expect(lines).toContain(`${name}:${value}`);
        }
      }
      for (const name of CASES.excluded_headers) {
        expect(lines.filter((line) => line.startsWith(`${name}:`))).toEqual([]);
      }
    });
  }

  // This runner skips any field it doesn't know, so an unknown or missing field fails here.
  it("uses only known fields", () => {
    const keys = (value: object) => Object.keys(value).sort().join(",");
    expect(keys(CASES)).toBe("cases,config,description,excluded_headers");
    for (const testCase of CASES.cases) {
      expect(keys(testCase)).toBe("expected,name,operation,params,service");
      expect(keys(testCase.expected)).toBe("headers,metadata,method,url");
    }
  });
});
