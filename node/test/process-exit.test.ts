// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { execFile } from "node:child_process";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { promisify } from "node:util";

import { afterAll, beforeAll, expect, it } from "vitest";

import { MockCache, Reply } from "./mock-cache.js";

const run = promisify(execFile);
const packageDir = fileURLToPath(new URL("../", import.meta.url));
const caCerts = fileURLToPath(new URL("./fixtures/tls/localhost.crt", import.meta.url));
let buildDir: string | undefined;

beforeAll(async () => {
  // Run emitted JavaScript outside Vitest so its handles cannot keep the child alive.
  const artifactsDir = join(packageDir, ".artifacts");
  await mkdir(artifactsDir, { recursive: true });
  buildDir = await mkdtemp(join(artifactsDir, "process-exit-"));
  await run(
    process.execPath,
    [
      "node_modules/typescript/bin/tsc",
      "-p",
      "tsconfig.esm.json",
      "--noCheck",
      "--declaration",
      "false",
      "--outDir",
      buildDir,
    ],
    { cwd: packageDir, timeout: 25_000 },
  );
}, 30_000);

afterAll(async () => {
  if (buildDir !== undefined) await rm(buildDir, { recursive: true, force: true });
});

async function expectScriptToExit(cache: MockCache, script: string): Promise<void> {
  await cache.start();
  try {
    const entry = pathToFileURL(join(buildDir!, "index.js")).href;
    const { stdout } = await run(
      process.execPath,
      [
        "--input-type=module",
        "--eval",
        `
          import assert from "node:assert/strict";
          import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
          import { ElastiCacheIntegrationsError, install } from ${JSON.stringify(entry)};

          const client = new DynamoDBClient({
            region: "us-east-1",
            credentials: { accessKeyId: "AKIAEXAMPLE", secretAccessKey: "secret-example" },
            maxAttempts: 1,
          });
          const connect = () => install({
            client,
            endpoint: ${JSON.stringify(cache.endpoint)},
            cacheName: "test-cache",
            caCerts: ${JSON.stringify(caCerts)},
            clientConfiguration: { connectionTimeout: 1_000, fallbackDuration: 0 },
          });
          const getItem = () => client.send(
            new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } }),
          );

          ${script}
          console.log("done");
        `,
      ],
      { cwd: packageDir, timeout: 8_000 },
    );
    expect(stdout.trim()).toBe("done");
  } finally {
    await cache.stop();
  }
}

it("exits with an idle TLS cache connection after receiving a deferred reply", async () => {
  const cache = new MockCache({
    tls: true,
    config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
    responseFn: () => {
      setTimeout(() => {
        cache.sendPush(1, Reply.ok(200, undefined, Buffer.from('{"Item":{"id":{"S":"42"}}}')));
      }, 20);
      return Reply.requestId(1);
    },
  });
  await expectScriptToExit(
    cache,
    `
      await connect();
      const result = await getItem();
      assert.equal(result.Item.id.S, "42");
    `,
  );
}, 10_000);

it("exits during reconnect backoff after a request waits out the outage", async () => {
  const cache = new MockCache({
    tls: true,
    config: { predicates: [{ expr: "operation == 'GetItem'", vars: {} }] },
    responseFn: () => {
      void cache.stop();
      return Reply.ok();
    },
  });
  await expectScriptToExit(
    cache,
    `
      await connect();
      await assert.rejects(getItem(), { name: "ElastiCacheIntegrationsConnectionLost" });
      await assert.rejects(getItem(), { name: "ElastiCacheIntegrationsCacheUnavailable" });
    `,
  );
}, 10_000);

it("stays alive through startup backoff until install rejects", async () => {
  const cache = new MockCache({ tls: true, helloResponse: Reply.error("WRONGPASS") });
  await expectScriptToExit(cache, `await assert.rejects(connect(), ElastiCacheIntegrationsError);`);
  expect(cache.cacheCommandLog.filter((command) => command === "hello").length).toBeGreaterThan(1);
}, 10_000);
