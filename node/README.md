# ElastiCache integration client for Node.js

ElastiCache Integrations provides a caching layer for AWS SDK for JavaScript v3 DynamoDB requests. It connects your DynamoDB client to an ElastiCache Serverless cache, sends supported requests through the cache, and serves cacheable responses.

## Requirements

- Node.js 22 or later.
- An AWS SDK for JavaScript v3 client, such as `DynamoDBClient` from `@aws-sdk/client-dynamodb`.

## Installation

The client is published to npm as [`@aws-sdk/aws-elasticache-integration-client`](https://www.npmjs.com/package/@aws-sdk/aws-elasticache-integration-client):

```bash
npm install @aws-sdk/aws-elasticache-integration-client
```

The package ships ESM and CommonJS builds, with TypeScript declarations for both.

## Basic Example

Before:

```ts
import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";

const dynamodb = new DynamoDBClient({ region: "us-east-1" });

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } }));
dynamodb.destroy();
```

After:

```ts
import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
import { install } from "@aws-sdk/aws-elasticache-integration-client";

const dynamodb = new DynamoDBClient({ region: "us-east-1" });
await install({
  client: dynamodb,
  endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
  cacheName: "my-cache",
});

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } })); // unchanged
dynamodb.destroy(); // also disconnects the cache
```

The only change to your application is the `install()` call; see [Configuration](#configuration) for its options. It adds the caching layer to the client you already built. Everything else stays the same: you call DynamoDB exactly as before, and the ElastiCache Integrations layer in front of it serves what it can from the cache.

`install()` connects to the cache before it resolves. If it cannot connect or [authenticate](#authentication), it rejects, and the client keeps working without the cache.

## How it works

- **You choose what is cached.** Requests for the tables and operations you choose go through the cache; everything else goes to DynamoDB as it would without ElastiCache Integrations. See [Request routing](#request-routing).
- **The cache going down does not take your application down.** If the connection to the cache is lost, the client reconnects in the background, and meanwhile requests go to DynamoDB as they would without ElastiCache Integrations in the middle. Once the connection is back, so is the caching layer. See [Reconnection and fallback](#reconnection-and-fallback) for the full details, and [Configuration](#configuration) to change how long requests wait and fall back.
- **Destroying the client also disconnects the cache.**

## Examples

### Your own HTTP settings for DynamoDB

Requests that do not go to the cache use the client's request handler to reach DynamoDB. To set its timeouts or connection pool, set it on the client as usual. This needs the `@smithy/node-http-handler` package:

```ts
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { NodeHttpHandler } from "@smithy/node-http-handler";
import { install } from "@aws-sdk/aws-elasticache-integration-client";

const dynamodb = new DynamoDBClient({
  region: "us-east-1",
  requestHandler: new NodeHttpHandler({ connectionTimeout: 1_000, requestTimeout: 2_000 }),
});
await install({
  client: dynamodb,
  endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
  cacheName: "my-cache",
});
```

## Configuration

The TypeScript declaration of each option has the full details.

Options of `install()`:

| Option                | Default                  | Meaning                                                             |
| --------------------- | ------------------------ | ------------------------------------------------------------------- |
| `client`              | required                 | The AWS SDK v3 client to add the cache to.                          |
| `endpoint`            | required                 | The cache endpoint, as `host:port`.                                 |
| `cacheName`           | required                 | The ElastiCache Serverless cache name.                              |
| `userId`              | `default.iam-user`       | The ElastiCache IAM user. See [Authentication](#authentication).    |
| `caCerts`             | Node.js default CA store | The path of a PEM CA bundle to verify the cache's certificate with. |
| `clientConfiguration` | all defaults             | The options below.                                                  |

Fields of `clientConfiguration` (type `ElastiCacheIntegrationsClientConfiguration`):

| Field                  | Default  | Meaning                                                                                                                                                                                |
| ---------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cacheRequestTimeout`  | 5000     | How long, in milliseconds, to wait for the cache to answer a request. See [Errors](#errors) for what a timeout looks like.                                                             |
| `connectionTimeout`    | 5000     | How long, in milliseconds, to try connecting at startup, and how long a request waits for a lost connection to come back. See [Reconnection and fallback](#reconnection-and-fallback). |
| `fallbackDuration`     | no limit | How long, in seconds, requests may go to DynamoDB while the cache is unreachable. `0` means never. See [Reconnection and fallback](#reconnection-and-fallback).                        |
| `cachePolicyOverrides` | none     | What to cache and for how long. See [Cache policy overrides](#cache-policy-overrides).                                                                                                 |
| `cachePolicyTimeout`   | 3600     | How long, in seconds, the client keeps using a cache policy the cache has not confirmed. See [Cache policy timeout](#cache-policy-timeout).                                            |
| `iamTokenRefreshDelay` | 300      | How long, in seconds, after a token is issued a replacement is signed in the background. 1 to 879. See [Authentication](#authentication).                                              |

### Cache policy overrides

| Key                                                      | Meaning                                                                                   |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `cached_tables`                                          | The tables to cache. Empty or absent means all tables.                                    |
| `cached_operations`                                      | The operations to cache, such as `GetItem` or `Query`. Empty or absent means all of them. |
| `item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl` | How long each kind of entry lives. `item_negative_ttl` covers "this item does not exist". |
| `item_write_behavior`                                    | How a write updates the cached item.                                                      |
| `table_overrides`                                        | The settings above for one table, keyed by table name.                                    |

### Example

```ts
import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
import { fromIni } from "@aws-sdk/credential-providers";
import { ElastiCacheIntegrationsError, install } from "@aws-sdk/aws-elasticache-integration-client";

// Your DynamoDB client, configured as usual. install() keeps all of it.
const dynamodb = new DynamoDBClient({
  region: "us-east-1",
  credentials: fromIni({ profile: "prod" }),
  maxAttempts: 5,
});

try {
  await install({
    client: dynamodb,
    endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
    cacheName: "my-cache",
    clientConfiguration: {
      cacheRequestTimeout: 2_000, // wait at most 2 s for the cache
      fallbackDuration: 60, // requests may go to DynamoDB for 60 s while the cache is unreachable
      // Cache only the Users and Sessions tables, and set their item lifetime.
      cachePolicyOverrides: { cached_tables: ["Users", "Sessions"], item_ttl: 60 },
    },
  });
} catch (error) {
  // The cache could not be reached or rejected the credentials. Run without it.
  if (!(error instanceof ElastiCacheIntegrationsError)) throw error;
  console.warn(`Starting without the cache: ${error.message}`);
}

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } }));
dynamodb.destroy();
```

## Request routing

Requests for the tables and operations you choose in [`cachePolicyOverrides`](#cache-policy-overrides) go through the cache. Without overrides, all of them do. Every other request goes straight to DynamoDB, with no round trip to the cache.

## Cache policy timeout

The client confirms its cache policy with the cache periodically. If those confirmations fail, the client keeps using the last policy the cache confirmed. [`cachePolicyTimeout`](#configuration) limits how long: once a refresh fails after that much time without a confirmation, the client drops the connection and reconnects, which fetches a fresh policy. Meanwhile requests follow [Reconnection and fallback](#reconnection-and-fallback). A successful refresh restarts the window, so a healthy connection is never dropped.

## Reconnection and fallback

**When the connection is lost.** The client starts reconnecting at once, in the background, even if the application is idle. Each attempt opens a new TLS connection, signs in with the current [IAM token](#authentication) and gets the current [cache policy](#request-routing). Attempts back off and continue until one succeeds or the client is destroyed.

**Requests during an outage.** A request the cache would serve first waits for the connection to come back, up to [`connectionTimeout`](#configuration). This wait is per outage, not per request: a request that arrives later in the same outage waits only for what is left of it. If the connection comes back in time, the request is served from the cache.

**After the wait.** If the connection is still down, the request goes to DynamoDB for as long as [`fallbackDuration`](#configuration) allows, counted from the end of the wait. The default has no limit, a positive value allows that many seconds, and `0` never allows it. Once the window ends, the request fails with a [retryable error](#errors) for the SDK to handle. A write sent to DynamoDB this way can leave a stale entry in the cache until its [TTL](#cache-policy-overrides) expires.

**A request already sent when the connection drops.** It fails with a [retryable error](#errors), whatever its operation, the same as when the connection to DynamoDB drops without the cache. Your SDK retry settings decide whether it is sent again, and the retry goes through the same wait and fallback window as any other request. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

**At startup.** `install()` keeps trying to connect until [`connectionTimeout`](#configuration) has passed, then rejects with the last attempt's failure: `ElastiCacheIntegrationsConnectionError` if it could not connect, `ElastiCacheIntegrationsAuthError` if the cache rejected its credentials, or `ElastiCacheIntegrationsOverridesRejected` if the cache rejected the [cache policy overrides](#cache-policy-overrides). It never leaves the client with a cache it could not connect to.

## Errors

Retries work as they do without the cache: your SDK retry settings decide whether, and how many times, a request is retried. A request that times out or fails because the cache could not complete it can be retried, for any operation. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

Your application handles these failures as it does without the cache: once the SDK's retries run out, `client.send()` rejects with the same error. That includes the cache being unreachable when requests may not go to DynamoDB (see [`fallbackDuration`](#configuration)).

Errors specific to the cache all extend `ElastiCacheIntegrationsError`. For example, `install()` rejects with one when it cannot connect to the cache:

```ts
import { ElastiCacheIntegrationsError, install } from "@aws-sdk/aws-elasticache-integration-client";

try {
  await install({
    client: dynamodb,
    endpoint: "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
    cacheName: "my-cache",
  });
} catch (error) {
  if (!(error instanceof ElastiCacheIntegrationsError)) throw error;
  // dynamodb is unchanged: it runs without the cache
}
```

## Authentication

The client signs in to the cache with an IAM authentication token, using the region and credentials of the client you pass to `install()`. See the [Configuration example](#example) for a client with its own credentials. The cache is accessed as the same identity as your DynamoDB requests, and credential rotation (SSO, assumed roles, container and instance credentials) keeps working.

That identity needs the `elasticache:Connect` permission on the cache and on the ElastiCache user ([`userId`](#configuration), `default.iam-user` by default).

Tokens are valid for 15 minutes. The client signs a replacement in the background before the current one expires. About every 15 minutes, it also authenticates each live connection again with the current token. If the cache rejects the token, the connection stays open with its current authentication, and the client signs another token and tries again, with backoff. If the cache does not answer the `AUTH` in time, or replies with neither `OK` nor an error, the client drops the connection and [reconnects](#reconnection-and-fallback). Set [`iamTokenRefreshDelay`](#configuration) to change when the replacement is signed.

Token signing and refresh come from the [ElastiCache developer toolkit](https://www.npmjs.com/package/@aws/developer-toolkit-elasticache).

## Logging

The client logs with `console.warn` and `console.error`.

## Development

See [DEVELOPER.md](DEVELOPER.md) for building, testing and releasing the client, and for how it works inside.
