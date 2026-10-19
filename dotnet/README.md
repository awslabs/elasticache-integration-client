# ElastiCache Integrations .NET

Thin ElastiCache Integrations client for the AWS SDK for .NET v4 DynamoDB client (`AWSSDK.DynamoDBv2`), on
`net8.0`.

`ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync` returns an ordinary `AmazonDynamoDBClient`. Calls that
the cache's routing predicates match are sent to the cache over RESP3 and TLS. Every other
call goes to DynamoDB through the SDK's normal HTTP path, unchanged. Application code keeps the
same SDK API, retries, unmarshalling and exceptions.

## Requirements

- The `net8.0` target framework. It is the only one. The test suite also runs on the .NET 10
  runtime (`DOTNET_ROLL_FORWARD=Major`) in CI, because .NET 8 support ends on 2026-11-10.
- `AWSSDK.DynamoDBv2` 4.0.107.3 or later, `AWSSDK.Core` 4.0.102.11 or later.
- An ElastiCache Serverless cache with ElastiCache Integrations enabled, and credentials allowed to
  `elasticache:Connect` to it and to call DynamoDB.

## Install

The package is not on nuget.org yet. Build it from a checkout and add the output folder as a
package source:

```sh
cd dotnet
dotnet pack src/Amazon.ElastiCache.Integrations -c Release -o ~/eci-packages
dotnet nuget add source ~/eci-packages --name eci-local
```

```xml
<PackageReference Include="Amazon.ElastiCache.Integrations" Version="0.0.1" />
```

The package has XML docs and a symbol package (`.snupkg`). Built from the GitHub repository, its
symbols carry Source Link, so a debugger can step into the client's sources.

## Quick start

```csharp
using Amazon;
using Amazon.DynamoDBv2;
using Amazon.DynamoDBv2.Model;
using Amazon.ElastiCache.Integrations;

var options = new ElastiCacheIntegrationsClientOptions
{
    Endpoint = "my-cache-abc123.serverless.use1.cache.amazonaws.com:6379",
    CacheName = "my-cache",
    Region = "us-east-1",
    // Overrides = new ElastiCacheIntegrationsOverrides { Ttl = 30 },
};

await using var dynamodb = await ElastiCacheIntegrationsClient.CreateDynamoDBClientAsync(
    options,
    new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 });

var item = await dynamodb.GetItemAsync(new GetItemRequest
{
    TableName = "elasticache-integrations-test",
    Key = new() { ["pk"] = new AttributeValue { S = "k1" } },
});
```

`CreateDynamoDBClientAsync` connects, authenticates with IAM and fetches the cache config before
it returns. A failure there throws, and nothing is left open. The config and credentials are
optional. Without credentials the SDK default chain is used, once, for both the cache and
DynamoDB. The client sets the config's `HttpClientFactory`, so a config that already has one is
refused.

`ElastiCacheIntegrationsDynamoDBClient` owns the cache session. `DisposeAsync` drains calls in flight for up to
`ConfigTimeout`, then closes. `Dispose` closes at once.

## Request routing

The cache config holds CEL predicates. They are compiled when a connection is set up and again
when a config refresh brings a new config. A predicate sees the request's `service`, `operation`,
`method`, `url`, `headers` (lowercase names, SigV4 headers removed) and parsed JSON `body`, plus
the variables in its `vars` object.

A call goes to the cache when any predicate is true. Otherwise the original signed request goes
to DynamoDB with no cache round trip. An evaluation error counts as no match.

The body is parsed only for a predicate that names `body`, such as a table filter. With no table
filter, the default predicate reads `service` and `operation` only, and the body is never parsed.
A body that cannot be read as a JSON object (malformed, not UTF-8, too deep) is no match for a
predicate that reads it. A predicate that does not read it can still route the call; the cache then rejects it with
`PARSE_ERROR`, and the call goes to DynamoDB, or fails in strict mode.

The client uses a config only when it can use all of it: format `1.x`, a `predicates` array, and
every entry an object with a string `expr`, an object `vars` if any, and an expression that
compiles. An empty array is valid and routes nothing. Any other config is never applied and logs
an error (event 1307) once, until a usable config arrives. At bring-up it fails the attempt. A
refresh that brings one keeps the current config, as any failed refresh does.

The proxy advertises ten operations: `GetItem`, `Query`, `Scan`, `PutItem`, `UpdateItem`,
`DeleteItem`, `BatchGetItem`, `BatchWriteItem`, `TransactGetItems` and `TransactWriteItems`.
Everything else, such as `DescribeTable`, goes to DynamoDB.

A DynamoDB error in a cached or proxied reply, such as `ResourceNotFoundException` or
`ConditionalCheckFailedException`, is thrown by the SDK exactly as for a direct call.

## Options

`ElastiCacheIntegrationsClientOptions` is validated before any network I/O. A bad value throws
`ElastiCacheIntegrationsConfigurationException`.

| Option | Default | Meaning |
|--------|---------|---------|
| `Endpoint` | required | The cache endpoint as `host:port`. An IPv6 host may be bracketed. |
| `CacheName` | required | The cache name, signed into the IAM token. |
| `Region` | the SDK config's region | The cache region. See [Region](#region). |
| `UserId` | `default.iam-user` | The ElastiCache user id sent with `AUTH`. |
| `Overrides` | empty | Config overrides sent with `aws.config`. See [Overrides](#overrides). |
| `AuthProvider` | IAM token from the credentials | Supplies the `AUTH` user id and token instead. |
| `UseTls` | `true` | Turn off only for local test endpoints. |
| `TlsServerName` | the endpoint host | The name for SNI and the certificate check, for tunnels and IP endpoints. |
| `CaCertificates` | the system store | Trusted roots that replace the system store. Copied when the client opens. |
| `InsecureSkipVerify` | `false` | Keep TLS but skip the certificate check. Local and test endpoints only. Wins over `CaCertificates` and logs a warning (event 1001). |
| `FallbackDuration` | infinite | How long calls go to DynamoDB while the cache is down. `TimeSpan.Zero` is strict mode. See [Reconnect and fallback](#reconnect-and-fallback). |
| `Reconnect` | see below | Connect retries and backoff. |
| `MaxPending` | `100` | Calls in flight on the connection. A call over the limit is not sent and fails with a retryable error, so the SDK retries it (event 1308, once per burst). |
| `MaxResponseSize` | 16 MiB | The largest reply accepted. A larger one closes the connection. |
| `OriginHandler` | built from the SDK config | The `HttpMessageHandler` for calls that go to DynamoDB. It is never disposed by the client. |
| `LoggerFactory` | stderr, Information and up | Where the client logs. See [Logging](#logging). |

### Timeouts

All timeouts are `TimeSpan` values. The defaults below are the current ones; `RequestTimeout`
and `ReconnectWait` are still under review and may change before GA.

| Option | Default | Limit for |
|--------|---------|-----------|
| `ConnectTimeout` | 1 s | TCP connect plus the TLS handshake. |
| `ConfigTimeout` | 2 s | Each of `AUTH`, `HELLO 3` and `aws.config`, counted like `RequestTimeout`. Also how long `DisposeAsync` drains. |
| `RequestTimeout` | 100 ms | One call's send and reply, counted once it may write. Waiting to write has the same limit. |
| `ReconnectWait` | 100 ms | How long a call waits for a reconnect in progress before it falls back. |
| `Reconnect.AttemptTimeout` | 5 s | One whole bring-up attempt: connect, `AUTH`, `HELLO 3` and `aws.config`. |
| `ReauthInterval` | 30 min | How often the live connection re-authenticates. Must be under 12 hours. Keep it under one hour: see [Authentication](#authentication). |
| `CachePolicyTimeout` | 1 h | How long a config the cache has not confirmed stays in use. See [Config refresh](#config-refresh). |

### Reconnect policy

`ElastiCacheIntegrationsReconnectPolicy`, set through `Options.Reconnect`:

| Option | Default | Meaning |
|--------|---------|---------|
| `ConnectAttempts` | `3` | Bring-up attempts before `CreateDynamoDBClientAsync` fails. Reconnects after a drop have no limit. |
| `AttemptTimeout` | 5 s | Limit for one bring-up attempt. |
| `BaseDelay` | 100 ms | The first backoff delay. It doubles up to `MaxDelay`. |
| `MaxDelay` | 3.2 s | The largest backoff delay. |
| `Jitter` | `0.2` | The random share of each delay, in [0, 1). |

### Overrides

`ElastiCacheIntegrationsOverrides` is sent once, with the first `aws.config` of each connection. A field left null
uses the cache's value. The cache resolves the overrides and returns the predicates the client
routes with. Overrides the cache rejects fail `CreateDynamoDBClientAsync` with
`ElastiCacheIntegrationsConfigurationException`.

| Field | Wire name | Meaning |
|-------|-----------|---------|
| `ExcludeOperations` | `exclude_operations` | Operations not to cache. |
| `ExcludeTables` | `exclude_tables` | Tables not to cache. |
| `Ttl` | `ttl` | The default cache TTL, in seconds. |
| `Nttl` | `nttl` | The default TTL for "not found" replies, in seconds. |
| `PopulateOnWrite` | `populate_on_write` | Repopulate cached items from committed writes instead of only invalidating. |
| `PopulateFullItem` | `populate_full_item` | Also write the full-item entry when repopulating. |
| `Tables` | `tables` | Per-table `Ttl`, `Nttl`, `PopulateOnWrite`, `PopulateFullItem`. When not empty, only these tables are cached. |
| `Extra` | as given | Fields this client does not model, sent unchanged. |

```csharp
Overrides = new ElastiCacheIntegrationsOverrides
{
    Ttl = 60,
    ExcludeTables = ["audit-log"],
    Tables = new Dictionary<string, ElastiCacheIntegrationsTableOverrides> { ["users"] = new() { Ttl = 300 } },
},
```

## Region

The cache region is `Options.Region`, else the SDK config's region, else the SDK's default
region chain. Reading the config's region also reads `AWS_REGION` and the profile. So a region
found there counts as the config's region, and it must match `Options.Region` when both are set:
a mismatch fails `CreateDynamoDBClientAsync` with `ElastiCacheIntegrationsConfigurationException`. The Node.js
client lets the explicit region win instead. When the config has neither a region nor a
`ServiceURL`, The client sets its region to the cache region.

## Authentication

By default each `AUTH` sends a short-lived IAM token, presigned with SigV4 from the credentials.
The credentials are read again for every `AUTH`, so rotated credentials are used.

The live connection re-authenticates every `ReauthInterval` in the background. A failed re-AUTH
keeps the connection serving and retries on backoff. When the cache answers a call
`-AUTH_ERROR`, the client re-authenticates at once, at most once per `Reconnect.MaxDelay`.
Until that succeeds, such calls go to
DynamoDB for up to `FallbackDuration` (events 1103 to 1105), then fail with
`ElastiCacheIntegrationsCacheUnavailableException`.

The cache's credential for the client (FAS) is expected to last about one hour after the `AUTH`
that vended it. If re-AUTH is still failing one hour after the last successful `AUTH`, the
connection counts as unauthenticated (event 1106). Calls then skip the cache and take the
`-AUTH_ERROR` path above, while re-AUTH keeps retrying. Any successful `AUTH`, including a
reconnect, ends it. With the default `ReauthInterval` of 30 minutes, re-AUTH has 30 minutes to
recover before this happens.

`AuthProvider` replaces token generation. Use it when you already hold a token, or for a local or
test endpoint that needs no AWS credentials. It is called for every `AUTH`.

## TLS

TLS is on by default, and the server's certificate and host name are checked against the system
store, or against `CaCertificates` when set. `TlsServerName` changes the name used for SNI and
the check. `InsecureSkipVerify` keeps TLS but waives the check; it is not a production option.

## Reconnect and fallback

Bring-up makes `ConnectAttempts` attempts, each capped by `AttemptTimeout`, with backoff between
them. A configuration error is not retried.

A dropped connection starts one background reconnect loop at once, even when the client is idle.
Each attempt opens a new connection and repeats `AUTH`, `HELLO 3` and `aws.config`, with capped
exponential backoff and jitter. The loop runs until it succeeds or the client is closed. Event
1303 is logged on the first failed attempt and event 1304 when it reconnects.

A call that finds no connection:

1. Waits up to `ReconnectWait` for a reconnect in progress. If the connection lands in time, the
   call is sent on it.
2. Then goes to DynamoDB with the original signed request, while inside `FallbackDuration`. The
   fallback window starts when `ReconnectWait` ends, not at the drop, so the wait does not use it
   up.
3. Once the window is spent, fails with `ElastiCacheIntegrationsCacheUnavailableException` until the cache is back
   (event 1305, once per outage).

`FallbackDuration = TimeSpan.Zero` is strict mode: while the client is open, a call never falls
back. It fails with `ElastiCacheIntegrationsCacheUnavailableException` when there is no connection or the cache
answers `-AUTH_ERROR`, and with `ElastiCacheIntegrationsServerException` when the cache refuses it
(`PARSE_ERROR`, `UNSUPPORTED`, `UNIMPLEMENTED`, `REGION_MISMATCH`, `CONFIG_INVALID`).

A call already sent on a connection that then fails is never replayed to DynamoDB by the client,
because the cache may have run it. It is returned to the SDK as an `HttpRequestException`, so the
SDK's retry policy decides, and the retry re-enters the client. This covers a reply timeout
(`RequestTimeout`), a lost connection and a corrupt reply.

A reply timeout fails only that call; the connection stays open. Replies arrive in order, so a
connection whose oldest in-band reply is missing for 10 s is treated as stuck: it is closed (event
1202), its calls fail as above, and it reconnects. 10 s is above the proxy's 5 s budget for a
DynamoDB call, so a slow miss does not close a healthy connection. With a `RequestTimeout` of 10 s
or more, an in-band reply timeout closes the connection at once.

A `MOVED` reply closes the connection and starts the reconnect. If it came in place of the reply,
the cache did not run the call, so the call is sent once more the way a call with no connection
goes. If it came after the cache accepted the call with a request id, the call fails with
`HttpRequestException` wrapping `ElastiCacheIntegrationsConnectionLostException`, and the SDK's retry policy
decides. `NOAUTH` always counts as not run: the engine refuses the command before the cache
sees it.

Replies may arrive out of order: the cache can answer with a request id and send the result
later as a RESP3 push. A slow call then does not block the calls behind it. A call that times
out is abandoned alone and the connection stays up; a late reply for it is dropped (event 1203).
If the cache answers `HELLO 3` with `ERR` (no `HELLO`) or `NOPROTO` (no RESP3), the client uses RESP2,
where replies come in order (event 1204). Any other error fails the attempt, which is retried.

## Config refresh

When the config has a `refresh_interval`, the client asks for the current config on that
interval. A new config replaces the predicates; an error, or a config the client cannot use,
keeps the current one and retries on backoff. A config is confirmed whenever a connection is set
up or a usable refresh reply arrives, new or unchanged. An unchanged reply after an unusable
config is not usable: the cache still holds that config.

`CachePolicyTimeout` bounds how long an unconfirmed config is used. Once it has passed, the next
failed refresh replaces the connection (event 1306). The normal reconnect follows, and
`FallbackDuration` applies while it runs. A config with no `refresh_interval` is never refreshed,
so it never goes stale.

## Errors

From `CreateDynamoDBClientAsync`:

| Exception | When |
|-----------|------|
| `ElastiCacheIntegrationsConfigurationException` | A bad option, a region mismatch, no region found, a config that already has an `HttpClientFactory`, no credentials, or overrides the cache rejects. |
| `ElastiCacheIntegrationsConnectionException` | Every bring-up attempt failed, including with a config the client cannot use. The exception is the last attempt's failure; its inner exception is the cause. |
| `OperationCanceledException` | The caller cancelled. |

From a call:

| Exception | When |
|-----------|------|
| The modeled SDK exception, for example `ResourceNotFoundException` | DynamoDB returned that error, through the cache or directly. |
| `HttpRequestException` | A call the SDK may retry failed in the client and the SDK's retries ran out. `InnerException` is the client error: `ElastiCacheIntegrationsTimeoutException`, `ElastiCacheIntegrationsConnectionLostException`, `ElastiCacheIntegrationsProtocolException`, `ElastiCacheIntegrationsPendingLimitException` (`MaxPending` calls were already in flight; this one was not sent), or a `ElastiCacheIntegrationsServerException` such as `ORIGIN_ERROR` or `INTERNAL_ERROR`. |
| `ElastiCacheIntegrationsCacheUnavailableException` | No cache connection, or `-AUTH_ERROR`, and the fallback window is spent or strict mode is on. Not retried by the SDK. |
| `ElastiCacheIntegrationsServerException` | Strict mode only: the cache rejected the call (`PARSE_ERROR`, `UNSUPPORTED`, `UNIMPLEMENTED`, `REGION_MISMATCH`, `CONFIG_INVALID`). Outside strict mode these calls go to DynamoDB. `Code` holds the code. |
| `TimeoutException` | The SDK's own `HttpClient` timeout fired while the call waited on the cache, as for a slow DynamoDB call. |
| `OperationCanceledException` | The caller cancelled. |
| `ObjectDisposedException` | The client was closed before or during the call. |

All client errors derive from `ElastiCacheIntegrationsException`. Messages never hold tokens, presigned URLs or
credentials.

## Closing

Close the client with `await using` or `DisposeAsync`. A call already sent to the cache when the
client is closed fails with `HttpRequestException` wrapping `ElastiCacheIntegrationsConnectionLostException`, or
with `OperationCanceledException`. A call not yet sent, such as one waiting for a reconnect or
retried by the SDK after the close, goes to DynamoDB while the SDK client is still open. Once the
SDK client is disposed, it throws `ObjectDisposedException` itself. Close is idempotent.

## Manual lifecycle

`ElastiCacheIntegrationsHandle` is the session alone, for applications that build their own SDK clients:

```csharp
await using var handle = await ElastiCacheIntegrationsHandle.OpenAsync(options, credentials, RegionEndpoint.USEast1);
var config = new AmazonDynamoDBConfig { RegionEndpoint = RegionEndpoint.USEast1 };
handle.Attach(config);
using var dynamodb = new AmazonDynamoDBClient(credentials, config);
```

One handle may be attached to many configs. It is thread safe. Close the handle when its
clients are no longer used. After that, attached clients send every call straight to DynamoDB,
as in the Node and Java clients. `IsOpen` is true while it has a live connection.

## Logging

The client logs through `Options.LoggerFactory`, under the category `Amazon.ElastiCache.Integrations`. Without one
it writes Information and higher to stderr, so warnings are never lost. An exception thrown by a
configured logger is dropped, so a broken logging provider cannot stop the client. Event ids:

| Id | Event | Level |
|----|-------|-------|
| 1001 | `InsecureSkipVerify` | Warning, once per process |
| 1002 | `SyncCallToOrigin` | Warning, once per handle |
| 1101 | `PeriodicFailed` (re-AUTH, config refresh) | Warning on the first failure, then Debug |
| 1102 | `PeriodicRecovered` | Information |
| 1103 | `AuthDegraded` | Warning when the window opens, then Debug |
| 1104 | `AuthRecovered` | Debug |
| 1105 | `AuthWindowExhausted` | Warning |
| 1106 | `AuthStale` | Error, once until an `AUTH` succeeds |
| 1201 | `ConnectionLost` | Information |
| 1202 | `HeadStalled` | Warning |
| 1203 | `LatePushDropped` | Debug |
| 1204 | `Resp2Fallback` | Information |
| 1301, 1302 | `ConfigApplied`, `BringUpAttemptFailed` | Debug |
| 1303 | `Reconnecting` | Warning on the first failed attempt, then Debug |
| 1304 | `Reconnected` | Information after a warned outage, else Debug |
| 1305 | `CacheUnavailable` | Warning, once per outage |
| 1306 | `ConfigStale` | Warning |
| 1307 | `ConfigUnusable` | Error, once until a usable config is applied |
| 1308 | `PendingLimitReached` | Warning, once per burst: again after 1 s with no call over the limit |

## Limitations

- Only `net8.0` is built. There is no `netstandard2.0` or .NET Framework target.
- Only the DynamoDB client is supported.
- SDK sync calls go straight to DynamoDB, with a one-time warning (event 1002). On `net8.0` the
  DynamoDB client has only async operations, so this affects only code that calls the SDK's
  `HttpClient` synchronously.
- A request with a streaming body is never routed; it goes to DynamoDB.
- `Options.Region` must match a region found in `AWS_REGION` or the profile (see [Region](#region)).
- Overrides are read once, when the client opens. Later changes to their collections are not sent.
- The package is not published to nuget.org yet.

## Development

Run from this directory with the .NET 8 SDK (`global.json` pins the 8.0.4xx band):

```sh
dotnet restore
dotnet format --verify-no-changes
dotnet build -warnaserror
dotnet test
scripts/pack-smoke.sh
```

`scripts/pack-smoke.sh` packs the library, checks the package contents and metadata, installs
the package into a throwaway console app and runs that app against the test suite's in-process
mock cache. It needs no AWS access. Tests never use the host's `~/.aws` files, AWS environment
variables or instance metadata.

[samples/Amazon.ElastiCache.Integrations.LiveValidation](samples/Amazon.ElastiCache.Integrations.LiveValidation/README.md) checks the
client against a real cache and compares every result with DynamoDB.
