import { Buffer } from "node:buffer";
import { randomBytes, randomUUID } from "node:crypto";
import dns from "node:dns";
import { existsSync } from "node:fs";
import { performance } from "node:perf_hooks";
import process, { loadEnvFile } from "node:process";
import { URL } from "node:url";

import {
  BatchGetItemCommand,
  BatchWriteItemCommand,
  DeleteItemCommand,
  DynamoDBClient,
  GetItemCommand,
  PutItemCommand,
  QueryCommand,
  ScanCommand,
} from "@aws-sdk/client-dynamodb";

import { install } from "../dist/esm/index.js";

const dotenvPath = new URL("../../.env", import.meta.url);
if (existsSync(dotenvPath)) {
  loadEnvFile(dotenvPath);
}

const cacheHost = env("ELASTICACHE_CACHE_HOST", "localhost");
const cachePort = positiveIntegerEnv("ELASTICACHE_CACHE_PORT", 6379);
const cacheName = env("ELASTICACHE_CACHE_NAME", "elasticache-integrations-e2e-cache");
const cacheUserId = env("ELASTICACHE_CACHE_USER_ID", undefined);
const tunnelHost = env("ELASTICACHE_TUNNEL_HOST", undefined);
const caCertPath = env("ELASTICACHE_CA_CERT", undefined);
const requestTimeoutMs = Math.round(
  positiveNumberEnv("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", 5) * 1000,
);
const concurrency = positiveIntegerEnv("ELASTICACHE_CONCURRENCY", 16);
// Defaults to us-east-1 when AWS_REGION is unset.
const region = env("AWS_REGION", "us-east-1");
const tableName = env("ELASTICACHE_TABLE_NAME", "ElastiCacheIntegrationsTestTable");
// A fresh ID per run forces the first ElastiCache Integrations read to be a cache miss and keeps
// concurrent runs from deleting each other's items.
const runId = env("ELASTICACHE_ITEM_ID", `elasticache-integrations-e2e-${randomUUID()}`);

const originalLookup = dns.lookup;
if (tunnelHost !== undefined) {
  dns.lookup = function lookup(hostname, options, callback) {
    if (hostname !== cacheHost) {
      return originalLookup.call(this, hostname, options, callback);
    }

    const resolvedCallback = typeof options === "function" ? options : callback;
    if (typeof options === "object" && options?.all) {
      return resolvedCallback(null, [{ address: tunnelHost, family: 4 }]);
    }
    return resolvedCallback(null, tunnelHost, 4);
  };
}

const origin = new DynamoDBClient({ region });
const seeded = [];
const integrations = new DynamoDBClient({ region });

try {
  await install({
    client: integrations,
    endpoint: `${cacheHost}:${cachePort}`,
    cacheName,
    ...(cacheUserId === undefined ? {} : { userId: cacheUserId }),
    ...(caCertPath === undefined ? {} : { caCerts: caCertPath }),
    clientConfiguration: { cacheRequestTimeout: requestTimeoutMs },
  });

  await scenarioCacheHit();
  await scenarioConcurrentReads();
  await scenarioQuery();
  await scenarioWritePassThrough();
  await scenarioOriginError();
  await scenarioLargeBinaryItem();
  await scenarioBatchReadsAndScan();
  await scenarioDeletePassThrough();
  process.stdout.write("SUCCESS: all ElastiCache Integrations E2E scenarios passed.\n");
} finally {
  for (const itemId of seeded) {
    await origin.send(new DeleteItemCommand(getRequest(tableName, itemId)));
  }
  integrations.destroy();
  origin.destroy();
  dns.lookup = originalLookup;
}

async function scenarioCacheHit() {
  process.stdout.write("== Cache hit after origin delete\n");
  const itemId = `${runId}-hit`;
  const item = await seed(itemId);
  const request = getRequest(tableName, itemId);

  const first = await timedGet(request, "GetItem #1");
  assertItem(first.Item, item);

  await deleteFromOrigin(itemId);

  const second = await timedGet(request, "GetItem #2 (after DynamoDB delete)");
  assertItem(second.Item, item);
  process.stdout.write(
    "PASS: ElastiCache Integrations returned the cached item after it was deleted from DynamoDB.\n",
  );
}

async function scenarioConcurrentReads() {
  process.stdout.write(`== ${concurrency} concurrent reads on distinct keys\n`);
  const items = [];
  for (let index = 0; index < concurrency; index += 1) {
    items.push(await seed(`${runId}-concurrent-${index}`, { quiet: true }));
  }

  async function readAll(label) {
    const started = performance.now();
    const responses = await Promise.all(
      items.map((item) => integrations.send(new GetItemCommand(getRequest(tableName, item.id.S)))),
    );
    const elapsed = performance.now() - started;
    // A cross-completed response would carry another key's item.
    responses.forEach((response, index) => assertItem(response.Item, items[index]));
    process.stdout.write(
      `${label}: ${items.length} GetItems in ${elapsed.toFixed(1)} ms, each matched its key\n`,
    );
  }

  await readAll("Cold reads");
  for (const item of items) {
    await origin.send(new DeleteItemCommand(getRequest(tableName, item.id.S)));
  }
  await readAll("Reads after DynamoDB delete");
  process.stdout.write(
    "PASS: concurrent responses matched their requests and were served from the cache.\n",
  );
}

async function scenarioQuery() {
  process.stdout.write("== Query through ElastiCache Integrations\n");
  const itemId = `${runId}-query`;
  const item = await seed(itemId);
  const response = await integrations.send(
    new QueryCommand({
      TableName: tableName,
      KeyConditionExpression: "id = :id",
      ExpressionAttributeValues: { ":id": { S: itemId } },
    }),
  );
  if (response.Items?.length !== 1) {
    throw new Error(`Unexpected Query response: ${JSON.stringify(response.Items)}`);
  }
  assertItem(response.Items[0], item);
  process.stdout.write("PASS: Query returned the seeded item.\n");
}

async function scenarioWritePassThrough() {
  process.stdout.write("== PutItem through ElastiCache Integrations\n");
  const itemId = `${runId}-write`;
  const item = newItem(itemId);
  seeded.push(itemId);
  await integrations.send(new PutItemCommand({ TableName: tableName, Item: item }));
  const stored = await origin.send(
    new GetItemCommand({ ...getRequest(tableName, itemId), ConsistentRead: true }),
  );
  assertItem(stored.Item, item);
  process.stdout.write(
    "PASS: a consistent DynamoDB read returned the item written through ElastiCache Integrations.\n",
  );
}

async function scenarioOriginError() {
  process.stdout.write("== Origin error on a missing table\n");
  const missingTable = `${runId}-missing-table`;
  try {
    await integrations.send(new GetItemCommand(getRequest(missingTable, "missing")));
  } catch (error) {
    if (error.name !== "ResourceNotFoundException") {
      throw new Error(`Expected ResourceNotFoundException, got ${error.name}`, { cause: error });
    }
    process.stdout.write("PASS: GetItem on a missing table raised ResourceNotFoundException.\n");
    return;
  }
  throw new Error(`GetItem on ${missingTable} succeeded`);
}

async function scenarioLargeBinaryItem() {
  process.stdout.write("== Large binary item\n");
  const itemId = `${runId}-large`;
  const item = largeItem(itemId);
  process.stdout.write(
    `Seeding ${tableName}/${itemId} (${item.payload.B.length} byte payload) in DynamoDB...\n`,
  );
  await origin.send(new PutItemCommand({ TableName: tableName, Item: item }));
  seeded.push(itemId);
  const request = getRequest(tableName, itemId);

  assertLargeItem((await timedGet(request, "GetItem #1")).Item, item);
  await deleteFromOrigin(itemId);
  assertLargeItem((await timedGet(request, "GetItem #2 (after DynamoDB delete)")).Item, item);
  process.stdout.write(
    "PASS: the large binary item matched byte for byte on the miss and the hit.\n",
  );
}

async function scenarioBatchReadsAndScan() {
  process.stdout.write("== BatchGetItem and Scan through ElastiCache Integrations\n");
  const prefix = `${runId}-batch-`;
  const items = [];
  for (let index = 0; index < 3; index += 1) {
    items.push(await seed(`${prefix}${index}`, { quiet: true }));
  }

  const batch = await integrations.send(
    new BatchGetItemCommand({
      RequestItems: { [tableName]: { Keys: items.map((item) => ({ id: item.id })) } },
    }),
  );
  if (Object.keys(batch.UnprocessedKeys ?? {}).length > 0) {
    throw new Error(`BatchGetItem left keys unprocessed: ${JSON.stringify(batch.UnprocessedKeys)}`);
  }
  assertItems(batch.Responses?.[tableName], items);

  const scan = await integrations.send(
    new ScanCommand({
      TableName: tableName,
      FilterExpression: "begins_with(id, :prefix)",
      ExpressionAttributeValues: { ":prefix": { S: prefix } },
    }),
  );
  assertItems(scan.Items, items);
  process.stdout.write("PASS: BatchGetItem and Scan returned every seeded item.\n");
}

async function scenarioDeletePassThrough() {
  process.stdout.write("== DeleteItem and BatchWriteItem through ElastiCache Integrations\n");
  const deleted = await seed(`${runId}-delete`);
  await integrations.send(new DeleteItemCommand(getRequest(tableName, deleted.id.S)));
  await assertAbsent(deleted.id.S);

  const batchDeleted = await seed(`${runId}-batch-delete`);
  const batchPut = newItem(`${runId}-batch-put`);
  seeded.push(batchPut.id.S);
  await integrations.send(
    new BatchWriteItemCommand({
      RequestItems: {
        [tableName]: [
          { PutRequest: { Item: batchPut } },
          { DeleteRequest: { Key: { id: batchDeleted.id } } },
        ],
      },
    }),
  );
  const stored = await origin.send(
    new GetItemCommand({ ...getRequest(tableName, batchPut.id.S), ConsistentRead: true }),
  );
  assertItem(stored.Item, batchPut);
  await assertAbsent(batchDeleted.id.S);
  process.stdout.write(
    "PASS: consistent DynamoDB reads reflected the deletes and the put written through ElastiCache Integrations.\n",
  );
}

async function timedGet(request, label) {
  const started = performance.now();
  const response = await integrations.send(new GetItemCommand(request));
  const elapsed = performance.now() - started;
  process.stdout.write(
    `${label}: HTTP ${response.$metadata.httpStatusCode ?? "unknown"} in ${elapsed.toFixed(1)} ms\n`,
  );
  return response;
}

function newItem(itemId) {
  return {
    id: { S: itemId },
    name: { S: "elasticache-integrations-widget" },
    description: { S: "e2e test item for ElastiCache Integrations caching" },
  };
}

function largeItem(itemId) {
  // DynamoDB caps an item at 400 KB, so this stays just under the limit.
  return {
    id: { S: itemId },
    payload: { B: randomBytes(380_000) },
    text: { S: "caf\u00e9 \u2713 \u65e5\u672c\u8a9e \u{1f680}" },
  };
}

function getRequest(table, itemId) {
  return { TableName: table, Key: { id: { S: itemId } } };
}

async function seed(itemId, { quiet = false } = {}) {
  const item = newItem(itemId);
  if (!quiet) {
    process.stdout.write(`Seeding ${tableName}/${itemId} directly in DynamoDB...\n`);
  }
  await origin.send(new PutItemCommand({ TableName: tableName, Item: item }));
  seeded.push(itemId);
  return item;
}

async function deleteFromOrigin(itemId) {
  process.stdout.write(`Deleting ${tableName}/${itemId} directly from DynamoDB...\n`);
  await origin.send(new DeleteItemCommand(getRequest(tableName, itemId)));
  await assertAbsent(itemId);
  process.stdout.write("Confirmed the item no longer exists in DynamoDB.\n");
}

async function assertAbsent(itemId) {
  const response = await origin.send(
    new GetItemCommand({ ...getRequest(tableName, itemId), ConsistentRead: true }),
  );
  if (response.Item !== undefined) {
    throw new Error(`DynamoDB still contains the item: ${JSON.stringify(response.Item)}`);
  }
}

function assertItem(actual, expected) {
  if (
    actual?.id?.S !== expected.id.S ||
    actual?.name?.S !== expected.name.S ||
    actual?.description?.S !== expected.description.S
  ) {
    throw new Error(`Unexpected GetItem response: ${JSON.stringify(actual)}`);
  }
}

function assertLargeItem(actual, expected) {
  if (
    actual?.id?.S !== expected.id.S ||
    actual?.text?.S !== expected.text.S ||
    !(actual?.payload?.B instanceof Uint8Array) ||
    !Buffer.from(actual.payload.B).equals(expected.payload.B)
  ) {
    throw new Error(`Unexpected large item for ${expected.id.S}`);
  }
}

function assertItems(actual, expected) {
  const byId = new Map((actual ?? []).map((item) => [item.id?.S, item]));
  if (actual?.length !== expected.length) {
    throw new Error(`Unexpected items: ${JSON.stringify(actual)}`);
  }
  for (const item of expected) {
    assertItem(byId.get(item.id.S), item);
  }
}

function env(name, defaultValue) {
  const value = process.env[name];
  return value === undefined || value === "" ? defaultValue : value;
}

function positiveNumberEnv(name, defaultValue) {
  const raw = env(name, String(defaultValue));
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) {
    throw new Error(`${name} must be a positive number, got ${raw}`);
  }
  return value;
}

function positiveIntegerEnv(name, defaultValue) {
  const raw = env(name, String(defaultValue));
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer, got ${raw}`);
  }
  return value;
}
