/**
 * The client never logs, or puts in an error, credentials, the IAM token or customer data.
 *
 * Every scenario runs a real DynamoDBClient against the mock cache with marker values in the
 * credentials, the request and the response, and records everything written to the console. It
 * then checks that no marker, no part of the IAM token the cache received, and nothing shaped like
 * an AWS key or SigV4 signature appears in the console output or in any error it raised.
 */
import { fileURLToPath } from "node:url";
import { inspect } from "node:util";

import { afterEach, describe, expect, it, vi } from "vitest";

import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";

import { install } from "../src/index.js";
import type { ElastiCacheIntegrationsClientConfiguration } from "../src/index.js";
import { MockCache, Reply } from "./mock-cache.js";
import type { MockCacheOptions } from "./mock-cache.js";

const CA_CERTS = fileURLToPath(new URL("./fixtures/tls/localhost.crt", import.meta.url));

const ACCESS_KEY = "AKIDLEAKCANARYACCESS";
const SECRET_KEY = "LEAKCANARYSECRETKEY";
const SESSION_TOKEN = "LEAKCANARYSESSIONTOKEN";
const REQUEST_VALUE = "LEAKCANARYREQUEST";
const RESPONSE_VALUE = "LEAKCANARYRESPONSE";
const MARKERS = [ACCESS_KEY, SECRET_KEY, SESSION_TOKEN, REQUEST_VALUE, RESPONSE_VALUE];

/** What a real secret looks like, in case one reaches a log some way the markers do not cover. */
const SECRET_SHAPES =
  /(?:AKIA|ASIA)[A-Z0-9]{16}|X-Amz-(?:Signature|Credential|Security-Token)|AWS4-HMAC-SHA256/i;

const CONSOLE_METHODS = ["log", "info", "warn", "error", "debug", "trace"] as const;

interface Scenario {
  cache: MockCacheOptions;
  configuration?: ElastiCacheIntegrationsClientConfiguration;
  /** Whether install() itself fails, as for a rejected AUTH. */
  installFails?: boolean;
  /** Whether the GetItem fails. */
  requestFails?: boolean;
}

const SCENARIOS: Record<string, Scenario> = {
  served: {
    cache: { responseBody: Buffer.from(JSON.stringify({ Item: { v: { S: RESPONSE_VALUE } } })) },
  },
  "rejected request": {
    cache: { response: Reply.error("ORIGIN_ERROR", "origin rejected") },
    requestFails: true,
  },
  "timed out": {
    cache: { failure: "hang" },
    configuration: { cacheRequestTimeout: 200 },
    requestFails: true,
  },
  dropped: { cache: { failure: "drop" }, requestFails: true },
  "auth rejected": {
    cache: {
      helloResponse: Reply.error("WRONGPASS", "invalid username-password pair or user is disabled"),
    },
    installFails: true,
  },
};

/** Everything an application could log about an error: its text, inspected form and causes. */
function errorText(error: unknown): string {
  return [String(error), inspect(error, { depth: Infinity, showHidden: true })].join("\n");
}

/**
 * A secret as it can appear in output: as text, and as the hex bytes `inspect()` prints for a
 * Buffer. The request marker is the table name, so it falls in the first 50 bytes it prints.
 */
function forms(secret: string): string[] {
  return [
    secret,
    Buffer.from(secret)
      .toString("hex")
      .replace(/(..)(?!$)/g, "$1 "),
  ];
}

/** The token and each long value in its query string: the signature, credential and session token. */
function tokenSecrets(token: string): string[] {
  const url = new URL(token.includes("://") ? token : `https://${token}`);
  return [token, ...[...url.searchParams.values()].filter((value) => value.length >= 16)];
}

/** The IAM tokens the cache received in HELLO ... AUTH and AUTH. */
function receivedTokens(cache: MockCache): string[] {
  return cache.receivedCommands.flatMap((command) => {
    const name = command[0]?.toString().toLowerCase();
    if (name === "hello" && command.length >= 5) return [command[4]!.toString()];
    if (name === "auth" && command.length >= 3) return [command[2]!.toString()];
    return [];
  });
}

describe("no credentials, IAM token or customer data in logs or errors", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each(Object.entries(SCENARIOS))("%s", async (_name, scenario) => {
    const written: string[] = [];
    for (const method of CONSOLE_METHODS) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => {
        written.push(args.map((arg) => inspect(arg, { depth: Infinity })).join(" "));
      });
    }
    const errors: unknown[] = [];
    const cache = new MockCache({
      tls: true,
      config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
      ...scenario.cache,
    });
    await cache.start();
    const dynamodb = new DynamoDBClient({
      region: "us-east-1",
      endpoint: "http://127.0.0.1:1",
      credentials: {
        accessKeyId: ACCESS_KEY,
        secretAccessKey: SECRET_KEY,
        sessionToken: SESSION_TOKEN,
      },
      maxAttempts: 1,
    });

    try {
      const installing = install({
        client: dynamodb,
        endpoint: cache.endpoint,
        cacheName: "test-cache",
        caCerts: CA_CERTS,
        clientConfiguration: { connectionTimeout: 1_000, ...scenario.configuration },
      });
      if (scenario.installFails) {
        errors.push(
          await installing.then(
            () => expect.fail("install succeeded"),
            (e) => e,
          ),
        );
      } else {
        await installing;
        const getItem = dynamodb.send(
          new GetItemCommand({ TableName: REQUEST_VALUE, Key: { id: { S: REQUEST_VALUE } } }),
        );
        if (scenario.requestFails) {
          errors.push(
            await getItem.then(
              () => expect.fail("GetItem succeeded"),
              (e) => e,
            ),
          );
        } else {
          expect((await getItem).Item).toEqual({ v: { S: RESPONSE_VALUE } });
        }
      }
    } finally {
      dynamodb.destroy();
      await cache.stop();
    }

    const output = [...written, ...errors.map(errorText)].join("\n");
    const tokens = receivedTokens(cache);
    expect(tokens.length, "the scenario never authenticated").toBeGreaterThan(0);
    if (!scenario.installFails) {
      expect(cache.cacheCommandLog, "the request never reached the cache").toContain("aws.request");
    }
    for (const marker of [...MARKERS, ...tokens.flatMap(tokenSecrets)].flatMap(forms)) {
      expect(output.includes(marker), `${marker.slice(0, 24)}... leaked:\n${output}`).toBe(false);
    }
    expect(output).not.toMatch(SECRET_SHAPES);
  });
});
