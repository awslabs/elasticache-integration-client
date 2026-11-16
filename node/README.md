# ElastiCache Integrations for Node.js

ElastiCache Integrations provides a caching layer for AWS SDK for JavaScript v3 DynamoDB requests. It connects your DynamoDB client to an ElastiCache Serverless cache, sends supported requests through the cache, and serves cacheable responses without contacting DynamoDB.

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

The only change to your application is the `install()` call; see [Configuration](#configuration) for its options. `install()` adds the caching layer to the client you already built and resolves with nothing. Everything else stays the same: you call DynamoDB through the same client exactly as before, every SDK setting on it is kept, and the ElastiCache Integrations layer in front of it serves what it can from the cache.

`install()` connects to the cache before it resolves. If it cannot connect or [authenticate](#authentication), it rejects, and the client stays usable without the cache. See [Errors](#errors) for what it rejects with.

## How it works

- **The cache decides what it serves.** When the client connects, the cache tells it which requests it wants. Those are sent to the cache; everything else goes to DynamoDB as it would without ElastiCache Integrations. See [Request routing](#request-routing).
- **The cache going down does not take your application down.** If the connection to the cache is lost, the client reconnects in the background, and meanwhile requests go to DynamoDB as they would without ElastiCache Integrations in the middle. Once the connection is back, so is the caching layer. See [Reconnection and fallback](#reconnection-and-fallback) for the full details, and [Configuration](#configuration) to change how long requests wait and fall back.
- **Destroying the client also disconnects the cache.** `destroy()` disconnects the cache first, then destroys the SDK client as usual. It is safe to call more than once.

## Examples

### Your own HTTP settings for DynamoDB

Requests that do not go to the cache are sent by the client's own request handler. Set its timeouts or connection pool on the client as usual, before or after `install()`. This example needs the `@smithy/node-http-handler` package:

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

### Aborting a request

A per-call `abortSignal` applies to requests served from the cache too. Aborting ends the wait for the cache, or for a lost connection to come back, at once with the SDK's `AbortError`, and the request does not fall back to DynamoDB:

```ts
const controller = new AbortController();
setTimeout(() => controller.abort(), 1_000);

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } }), {
  abortSignal: controller.signal,
});
```

## Configuration

Every option has a default, so set only what you change. `install()` rejects with `ElastiCacheIntegrationsConfigError` before it connects if a value is not usable, or if an option or field name is not one of those below.

Options of `install()`:

| Option                | Default                  | Meaning                                                                                                                |
| --------------------- | ------------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| `client`              | required                 | The AWS SDK v3 client to add the cache to. Its region, credentials and request handler are used.                       |
| `endpoint`            | required                 | The cache endpoint, as `host:port`.                                                                                    |
| `cacheName`           | required                 | The ElastiCache Serverless cache name.                                                                                 |
| `userId`              | `default.iam-user`       | The ElastiCache IAM user. See [Authentication](#authentication).                                                       |
| `verify`              | `true`                   | Verify the cache's TLS certificate. `false` is for local testing only. See [Authentication](#authentication).          |
| `caCerts`             | Node.js default CA store | The path of a PEM CA bundle file to verify the cache's certificate with. `install()` reads it once, before connecting. |
| `clientConfiguration` | all defaults             | The options below.                                                                                                     |

Fields of `clientConfiguration` (type `ElastiCacheIntegrationsClientConfiguration`):

| Field                  | Default  | Meaning                                                                                                                                                                                |
| ---------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cacheRequestTimeout`  | 5000     | How long, in milliseconds, to wait for the cache to answer a request. See [Errors](#errors) for what a timeout looks like.                                                             |
| `connectionTimeout`    | 5000     | How long, in milliseconds, to try connecting at startup, and how long a request waits for a lost connection to come back. See [Reconnection and fallback](#reconnection-and-fallback). |
| `fallbackDuration`     | no limit | How long, in seconds, requests may go to DynamoDB while the cache is unreachable. `0` means never. See [Reconnection and fallback](#reconnection-and-fallback).                        |
| `cachePolicyOverrides` | none     | What to cache and for how long. See [Cache policy overrides](#cache-policy-overrides).                                                                                                 |
| `cachePolicyTimeout`   | 3600     | How long, in seconds, the client keeps using a cache policy the cache has not confirmed. See [Cache policy timeout](#cache-policy-timeout).                                            |
| `iamTokenRefreshDelay` | 300      | How long, in seconds, after a token is issued a replacement is signed in the background. 1 to 879. See [Authentication](#authentication).                                              |

Timeouts are positive whole numbers of milliseconds. `fallbackDuration`, `cachePolicyTimeout` and `iamTokenRefreshDelay` are whole numbers of seconds.

### Cache policy overrides

`cachePolicyOverrides` is a plain object sent to the cache as JSON:

| Key                                                      | Meaning                                                                                   |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `cached_tables`                                          | The tables to cache. Empty or absent means all tables.                                    |
| `cached_operations`                                      | The operations to cache, such as `GetItem` or `Query`. Empty or absent means all of them. |
| `item_ttl`, `item_negative_ttl`, `query_ttl`, `scan_ttl` | How long each kind of entry lives. `item_negative_ttl` covers "this item does not exist". |
| `item_write_behavior`                                    | How a write updates the cached item.                                                      |
| `table_overrides`                                        | The settings above for one table, keyed by table name.                                    |

The cache ignores a key it does not recognise, so a misspelled key is dropped without an error. `install()` copies the object, so changing it afterwards has no effect. A value JSON cannot represent, such as a `BigInt` or a non-finite number, makes `install()` reject with `ElastiCacheIntegrationsConfigError` instead of being sent as `null`.

### Example

```ts
import { DynamoDBClient, GetItemCommand } from "@aws-sdk/client-dynamodb";
import { fromIni } from "@aws-sdk/credential-providers";
import {
  ElastiCacheIntegrationsConfigError,
  ElastiCacheIntegrationsError,
  install,
} from "@aws-sdk/aws-elasticache-integration-client";

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
  // A wrong option is a bug; anything else means the cache could not be used. Run without it.
  if (!(error instanceof ElastiCacheIntegrationsError)) throw error;
  if (error instanceof ElastiCacheIntegrationsConfigError) throw error;
  console.warn(`Starting without the cache: ${error.message}`);
}

await dynamodb.send(new GetItemCommand({ TableName: "Users", Key: { id: { S: "42" } } }));
dynamodb.destroy();
```

## Request routing

When the client connects, the cache sends it a set of routing rules. The client checks each request against them itself: a request that matches any rule is sent to the cache, and every other request goes straight to DynamoDB, with no round trip to the cache.

A rule can look at the request's service, operation, method, URL, headers and JSON body. The service is the client's service ID in lowercase, with spaces replaced by `-`, so `DynamoDB` becomes `dynamodb`. A rule that fails to evaluate, or a body that is not a JSON object, counts as no match. A request whose body is a stream goes to DynamoDB without being checked, because reading the stream would consume it. A rule the client cannot understand at all rejects the whole set, because using only the rest would route requests differently from what the cache intended: connecting fails and is retried, and a refresh keeps the previous rules.

The client asks the cache for its current rules and [cache policy](#cache-policy-overrides) periodically, at an interval the cache chooses, and applies an update all at once, so a request never sees half of one.

One connection carries at most 100 requests waiting for a reply. A request waits for a free slot, but if it cannot get one within three quarters of [`cacheRequestTimeout`](#configuration), it is not sent to the cache and goes to DynamoDB instead, whatever its operation.

## Cache policy timeout

If those periodic [refreshes](#request-routing) fail, the client keeps using the last policy the cache confirmed. [`cachePolicyTimeout`](#configuration) limits how long: once a refresh fails after that much time without a confirmation, the client drops the connection and reconnects, which fetches a fresh policy. Meanwhile requests follow [Reconnection and fallback](#reconnection-and-fallback). A successful refresh, including one that reports no change, restarts the window, and so does each new connection, so a healthy connection is never dropped.

A refresh that times out, or loses the connection, starts reconnecting at once instead of waiting for the timeout.

## Reconnection and fallback

**When the connection is lost.** The client starts reconnecting at once, in the background, even if the application is idle. Each attempt opens a new TLS connection, signs in with a fresh [IAM token](#authentication) and gets the current [cache policy](#request-routing). Attempts back off and continue until one succeeds or the client is destroyed.

**Requests during an outage.** A request the cache would serve first waits for the connection to come back, up to [`connectionTimeout`](#configuration). This wait is per outage, not per request: a request that arrives later in the same outage waits only for what is left of it. If the connection comes back in time, the request is served from the cache.

**After the wait.** If the connection is still down, the request goes to DynamoDB for as long as [`fallbackDuration`](#configuration) allows, counted from the end of the wait. The default (`undefined`), `null` or a negative value has no limit, a positive value allows that many seconds, and `0` never allows it. Once the window ends, the request fails with `ElastiCacheIntegrationsCacheUnavailable`, a [retryable error](#errors) for the SDK to handle. This applies to every routed request, including writes. A write sent to DynamoDB this way can leave a stale entry in the cache until its [TTL](#cache-policy-overrides) expires. Requests the cache would not serve keep going to DynamoDB after the window ends. The wait and the window start again with the next outage.

**A request already sent when the connection drops.** It fails with a [retryable error](#errors), whatever its operation, the same as when the connection to DynamoDB drops without the cache. Your SDK retry settings decide whether it is sent again, and the retry goes through the same wait and fallback window as any other request. With `fallbackDuration: 0`, that retry does not wait again within the same outage, so it fails with `ElastiCacheIntegrationsCacheUnavailable` unless the connection is already back. As without the cache, a retried write may be applied twice if the first attempt reached DynamoDB.

**At startup.** `install()` keeps trying to connect until [`connectionTimeout`](#configuration) has passed, then rejects with the last attempt's failure. See [Errors](#errors) for what that can be. It never leaves the client with a cache it could not connect to.

## Errors

A request's error is not wrapped. The SDK's retry strategy rethrows the last attempt's error as it is, so `client.send()` rejects with the client's error itself, with the SDK's `$metadata.attempts` added.

Three error classes are exported:

| Class                                     | Meaning                                                                                                                                      |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `ElastiCacheIntegrationsError`            | The base class of the client's other errors.                                                                                                 |
| `ElastiCacheIntegrationsConfigError`      | An option or client that `install()` cannot use. Thrown before connecting.                                                                   |
| `ElastiCacheIntegrationsCacheUnavailable` | The cache is unreachable and the [fallback window](#reconnection-and-fallback) has ended. It does not extend `ElastiCacheIntegrationsError`. |

The other errors extend `ElastiCacheIntegrationsError` but are not exported; tell them apart by `error.name`.

**What `install()` rejects with.**

- `ElastiCacheIntegrationsConfigError` for an unusable option, a value that is not an AWS SDK v3 client, a client with no region, or a client whose credentials cannot be resolved (the SDK's error is the `cause`). A client that already has the cache installed gets this too; after `destroy()` it can be installed into again.
- `ElastiCacheIntegrationsConnectionError` if it could not connect in time. This includes the cache rejecting the client's credentials at sign-in: the error's `cause` is then named `ElastiCacheIntegrationsHandshakeRejected`.
- `ElastiCacheIntegrationsOverridesRejected` if the cache rejected the [cache policy overrides](#cache-policy-overrides), or `ElastiCacheIntegrationsConfigUnevaluable` if the cache sent a routing rule the client cannot understand.
- `ElastiCacheIntegrationsError` itself if the IAM toolkit rejected the cache name or user ID, or could not sign a token.

**What a request rejects with.** The SDK retries an error marked retryable, as it would a transient network error. These are:

- `ElastiCacheIntegrationsCacheUnavailable`, always.
- `ElastiCacheIntegrationsConnectionLost` after the connection dropped, for every operation.
- `ElastiCacheIntegrationsTimeoutError` when the cache did not answer within [`cacheRequestTimeout`](#configuration), and an error the cache returns because DynamoDB failed (`ORIGIN_ERROR`), only for the read-only operations listed in [`shared/idempotent-operations.json`](../shared/idempotent-operations.json), such as `GetItem`, `Query` and `Scan`. For other operations they are not retried, because the request may already have been applied.

Other failures are not retried. An error reply from the cache, such as a rejected request, has `name` `ElastiCacheIntegrationsRejectedError` and the cache's error `code`. A reply the client cannot read has `name` `ElastiCacheIntegrationsProtocolError`. If the cache rejects a request because its sign-in is no longer valid, the client reconnects and sends that request to DynamoDB, or rejects it with `ElastiCacheIntegrationsCacheUnavailable` if `fallbackDuration` is `0`.

## Authentication

The client signs in to the cache with an IAM authentication token, using the region and credentials of the client you pass to `install()`. See the [Configuration example](#example) for a client with its own credentials. The cache is accessed as the same identity as your DynamoDB requests, and credential rotation (SSO, assumed roles, container and instance credentials) keeps working.

That identity needs the `elasticache:Connect` permission on the cache and on the ElastiCache user ([`userId`](#configuration), `default.iam-user` by default).

Tokens are valid for 15 minutes. The client signs a replacement in the background before the current one expires, and authenticates each live connection again with it about every 15 minutes. If the cache rejects the new token, the connection stays open with its current authentication, and the client signs another token and tries again, with backoff. If the cache does not answer the `AUTH`, or its reply cannot be read, the client drops the connection and [reconnects](#reconnection-and-fallback). Set [`iamTokenRefreshDelay`](#configuration) to change when the replacement is signed.

Token signing and refresh come from the [ElastiCache developer toolkit](https://www.npmjs.com/package/@aws/developer-toolkit-elasticache). Its [token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/main/node#caching-and-background-refresh) describes refresh and retry in detail. `destroy()` stops it.

The connection to the cache always uses TLS. The cache's certificate is verified against Node.js's default CA store (its bundled Mozilla roots, plus the operating system's store only when Node.js runs with system CA support enabled), or against [`caCerts`](#configuration) when you set it. `verify: false` keeps TLS but skips the certificate check, and emits a process warning, `ElastiCacheIntegrationsInsecureTlsWarning`, the first time a connection uses it. It is for local or test endpoints only; use `caCerts` wherever the endpoint's CA is available.

## Logging

The client logs with `console.warn` and `console.error`. Failed config refreshes and failed background tasks are logged as warnings. A cache policy the cache rejected (`CONFIG_INVALID`), or a routing rule the client cannot understand, never fixes itself, so it is logged as an error every time it happens, including on each connection attempt.

## Development

See [DEVELOPER.md](DEVELOPER.md) for building, testing and releasing the client, and for how it works inside.
