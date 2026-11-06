# Cache Java

ElastiCache Integrations for AWS SDK for Java v2 DynamoDB clients.

Published releases use the Maven coordinates
`software.amazon.elasticache:aws-elasticache-integration-client:<version>`.
The Java package is `software.amazon.elasticache.integrations`.

Cache connects and authenticates before returning the SDK client. Requests are
sent to Cache when the configured predicates match and otherwise use the
configured DynamoDB origin transport. While reconnecting, requests that have
not been sent to Cache first wait up to `connectionTimeoutMs` for the new
connection, then use origin fallback until the configured fallback window
expires. After a request is sent, indeterminate failures fall back only
when the connection drops and the operation is in the shared replay-safe
catalog. While the connection remains healthy, an `ORIGIN_ERROR` is returned as
SDK-retryable only for a cataloged operation. `AUTH_ERROR` closes the
stale connection and passes the rejected request through while reconnecting.
Other RESP errors, failures for non-idempotent operations, malformed responses,
an expired fallback window, and shutdown are surfaced without starting an
origin request. SDK retries, response deserialization, and caller-provided
override configuration remain in the normal SDK pipeline.

IAM token generation and refresh come from the Amazon ElastiCache developer
toolkit, the
[`software.amazon.elasticache:developer-toolkit-elasticache`](https://central.sonatype.com/artifact/software.amazon.elasticache/developer-toolkit-elasticache)
artifact on Maven Central. The toolkit validates and lowercases cache names and user
IDs, resolves fresh AWS credentials for every newly signed token, and owns the
SigV4 signing contract. Cache sends the resulting bearer token only in the
TLS-protected `HELLO 3 AUTH <user> <token>` handshake, which also confirms RESP3
before the connection becomes usable. Cache then periodically repeats plain
`AUTH` on each live connection using the manager's latest token. A rejected
refresh is retried with reconnect backoff while the connection keeps serving
with its last valid authentication state. A timed-out or malformed AUTH reply
drops the connection for FIFO safety and starts normal recovery. Origin request
signing remains owned by the AWS SDK.

## Request routing

Opening a Cache session compiles the CEL predicates returned by
`aws.config`. Each expression can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary variables
bound by the predicate's `vars` object.

A request is sent to Cache when any predicate evaluates to `true`. Otherwise,
the original signed SDK request is passed to the configured origin HTTP
transport without a cache round trip. This supports the proxy's read-through
and write-through routing, including table allowlists for batch and transaction
operations.

Evaluation errors and malformed or non-object JSON bodies count as no match. A
predicate that cannot be compiled is different: one bad entry rejects the whole
config rather than caching by the entries that did compile, because those would
route a different set of requests than the proxy resolved. Opening the session
fails on such a config, a reconnect retries, and a refresh keeps the last good
config. The resolved config is
compiled when the session opens, after every successful reconnect, and whenever
a periodic refresh returns an updated config. Config and compiled predicates
are published together, so requests never observe values from different
refreshes.

The `aws.config` response must include `refresh_interval` as a positive JSON
integer within the Java `Integer` range. Missing, null, fractional, non-positive,
and overflowing values are protocol errors. The session starts a daemon
refresher using this interval. After the initial explicit override map is
acknowledged, refreshes use the zero-argument `aws.config` form to indicate
that the overrides are unchanged. A RESP3 null reply leaves the current config
and interval unchanged; a valid config reply atomically replaces the config and
predicates and sets the next refresh interval.

Periodic refresh pauses while reconnecting, and a late reply from a closed or
replaced connector is ignored. See [Cache policy timeout](#cache-policy-timeout) for
what happens when refreshes fail.

## Cache policy timeout

A refresh that fails changes nothing: the last config the server confirmed
stays in force and the next refresh retries. A config the server sends, an
empty "unchanged" reply, and each new connection resolving its own config all
count as confirmation.

`cachePolicyTimeoutSeconds` bounds that tolerance. It is a positive whole number
of seconds and defaults to one hour. A refresh that fails past it means the
config can no longer be trusted, so Cache drops the connection, and bringing
up the replacement resolves a fresh config. A refresh that succeeds restarts
the window, so a healthy connection is never dropped. Requests fall back
meanwhile under the configured origin-fallback window. A request already in
flight when the connection drops falls back only when it is idempotent. Set the
duration with `ElastiCacheIntegrationsClientConfiguration.Builder.cachePolicyTimeoutSeconds(long)`.

A `CONFIG_INVALID` reply (the proxy rejected the overrides,
`ElastiCacheIntegrationsOverridesRejected`) and a config with a predicate this client cannot
compile (`ElastiCacheIntegrationsConfigUnevaluable`) never fix themselves, so they log at
`SEVERE` on every occurrence, including each connection attempt. On refresh
they still keep the last good config until the cache policy timeout drops the
connection. Refresh failures that kill the connection start recovery
immediately.

## Installing into a client

`install` takes the builder of an AWS SDK client that the application already
configures and returns the built client. The client keeps its own region,
credentials, override configuration (interceptors, retries, timeouts, metrics)
and endpoint. The cache is authenticated with the region and credentials that
the built client resolved.

An AWS SDK for Java v2 client cannot be changed after `build()`, so `install`
takes the builder instead of a finished client.

```java
DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache:6379")
        .cacheName("my-cache")
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));

ddb.getItem(...);  // unchanged
ddb.close();       // disconnects the cache, then closes the client as usual
```

`installAsync` does the same for an asynchronous builder and returns the
asynchronous client:

```java
DynamoDbAsyncClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache:6379")
        .cacheName("my-cache")
        .installAsync(DynamoDbAsyncClient.builder().region(Region.US_EAST_1));

CompletableFuture<ListTablesResponse> response = ddb.listTables();
```

`install` and `installAsync` return after the connection, the
`HELLO 3 AUTH` handshake and the `aws.config` exchange succeed. If a step
fails, they close what they created and throw the error. When the startup
deadline (`connectionTimeoutMs`) passes after a connection failure, they throw
`ElastiCacheIntegrationsConnectionError` ("opening a connection timed out after
Nms") with the last failure as its cause. They throw
`ElastiCacheIntegrationsConfigError` when an option or the client cannot be
used, for example a client with no region, a client built with
`AnonymousCredentialsProvider`, or a client whose credentials provider cannot
resolve credentials. In the last case, the SDK's exception is the cause.

| Builder method | Default | Meaning |
|---|---|---|
| `endpoint(String)` | required | Cache endpoint as `host:port`. IPv6 addresses use brackets, as in `[::1]:6379`. |
| `cacheName(String)` | required | The ElastiCache Serverless cache name. |
| `userId(String)` | `default.iam-user` | ElastiCache IAM user id. |
| `verify(boolean)` | `true` | Verify the cache's TLS certificate and host name. `false` is for self-signed test rigs only. |
| `caCerts(Path)` | system trust store | PEM CA bundle file to verify the cache's certificate against. The host name is still checked. An unreadable or invalid file throws `ElastiCacheIntegrationsConfigError`. |
| `clientConfiguration(ElastiCacheIntegrationsClientConfiguration)` | all defaults | See [Configuration](#configuration). |
| `originHttpClient(SdkHttpClient)` | SDK Apache HTTP client | Transport for requests that go to the origin service. |
| `originAsyncHttpClient(SdkAsyncHttpClient)` | SDK Netty HTTP client | Transport for origin requests from `installAsync`. |
| `asyncExecutor(Executor)` | bounded daemon pool | Executor for the blocking cache socket requests of `installAsync`. |
| `asyncMaxBufferedRequestBytes(int)` | 16 MiB | Largest asynchronous request body Cache buffers. |
| `asyncMaxConcurrentBodyCollectors(int)` | 8 | Most asynchronous request bodies collected at once. |

These options describe the connection to the cache, which the SDK client knows
nothing about. TLS to AWS and everything else about reaching the service stay
the client's own configuration.

### Transport

The client that `install` builds gets the cache interceptor added to a copy of
the builder's override configuration, so the interceptors, retries, timeouts and
metrics that the builder already has survive. It also gets an HTTP transport
that reads from the cache first and falls back to the origin service.

To set HTTP settings such as socket timeouts, connection timeouts or the
connection pool, build the HTTP client and pass it as `originHttpClient` (or
`originAsyncHttpClient`). Leave it off the SDK builder. Requests that the cache
does not serve go through that same client, with all of its settings:

```java
SdkHttpClient origin = ApacheHttpClient.builder()
        .socketTimeout(Duration.ofSeconds(2))
        .connectionTimeout(Duration.ofSeconds(1))
        .build();

DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache:6379")
        .cacheName("my-cache")
        .originHttpClient(origin)
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));
```

`install` sets the SDK builder's `httpClientBuilder` to the cache transport, and
the SDK builder has no getter for an HTTP client that the application already
set. A builder that already has an `httpClient` or an `httpClientBuilder` makes
`install` throw `ElastiCacheIntegrationsConfigError` before it connects to the
cache, so those settings are never lost without an error. `install` also throws
`ElastiCacheIntegrationsConfigError` for a builder it cannot inspect, such as a
wrapper around the builder that the SDK client's `builder()` method returns.
The SDK closes the
transport that Cache creates when the application closes the client. Cache
closes the origin transport only when it created it. A transport or executor
that you supply stays open.

`install` puts the builder's override configuration back the way it was and
clears its `httpClientBuilder`, whether it succeeds or fails. Each `install` on
the same builder returns a separate client with its own cache connection, so
the builder can be installed into again after a client is closed or after a
failed bring-up.

### Synchronous request bodies

A request whose body comes from an `InputStream` or a file passes through to
the origin transport without Cache. This covers `RequestBody.fromInputStream`,
`RequestBody.fromFile`, and `RequestBody.fromContentProvider` without a content
length. Reading a stream for Cache would consume it before the origin send, and
a file would be buffered whole. Other bodies, including DynamoDB requests, are
read for Cache.

### Asynchronous clients

DynamoDB operations are asynchronous and do not block SDK event-loop threads
while waiting for Cache.

A request whose `AsyncRequestBody` is not bytes passes through to the origin
transport without Cache. This covers `AsyncRequestBody.fromInputStream`,
`fromFile`, `fromPublisher`, and custom `AsyncRequestBody` implementations.
Cache buffers the remaining request bodies, including DynamoDB requests, so the
same signed request can be replayed safely during origin fallback. Only bodies
with a known content length are admitted. Buffering is limited to 16 MiB per
request and eight concurrent collectors by default. Configure these limits with
`asyncMaxBufferedRequestBytes(int)` and `asyncMaxConcurrentBodyCollectors(int)`.
A request with an unknown length, or one that exceeds either limit, bypasses
Cache and uses the origin transport.

Cache's socket request API is blocking, so the asynchronous adapter runs it on
a bounded daemon executor. If the executor rejects a request because it is at
capacity, Cache sends the buffered request through the origin fallback. When
`fallbackDurationSeconds` is 0, a request the predicates route to Cache fails
with `ElastiCacheIntegrationsCacheUnavailable` instead.

## Configuration

Optional settings go in one `ElastiCacheIntegrationsClientConfiguration`,
passed as `clientConfiguration`. Every option has a default, so set only what
you change. Cache policy settings are sent to the cache through `aws.config`.

```java
ElastiCacheIntegrationsClient.builder()
        .endpoint("my-cache:6379")
        .cacheName("my-cache")
        .clientConfiguration(ElastiCacheIntegrationsClientConfiguration.builder()
                .cacheRequestTimeoutMs(2_000)
                .fallbackDurationSeconds(60)
                .cachePolicyOverrides(Collections.singletonMap(
                        "cached_tables", Arrays.asList("Users", "Sessions")))
                .iamTokenRefreshDelay(Duration.ofSeconds(300))
                .build())
        .install(DynamoDbClient.builder().region(Region.US_EAST_1));
```

| Option | Default | Meaning |
|---|---|---|
| `cacheRequestTimeoutMs(long)` | 5000 | How long to wait for every command after the connection is ready, including cache requests, re-AUTH, and config refresh, in milliseconds. Must be positive and at most 2147483647. |
| `connectionTimeoutMs(long)` | 5000 | How long to try at startup, and how long a request waits for reconnection after a connection is lost, in milliseconds. It also bounds each startup attempt through connect, HELLO/authentication, and configuration. Must be positive and at most 2147483647. |
| `fallbackDurationSeconds(long)` | negative (indefinite) | How long requests may go to the origin service after the `connectionTimeoutMs` wait ends without a connection. Zero means never. See [Reconnection and fallback](#reconnection-and-fallback). |
| `cachePolicyOverrides(Map<String, Object>)` | none | Settings that change what Cache caches: `cached_tables` and `cached_operations` allowlists, and the cache entry lifetimes. Values must be JSON values (null, string, boolean, finite number, list, or map with string keys). The map is checked and deep-copied when the configuration is built. |
| `cachePolicyTimeoutSeconds(long)` | 3600 | How long a cache policy may be used without confirmation from the cache. Must be positive. See [Cache policy timeout](#cache-policy-timeout). |
| `iamTokenRefreshDelay(Duration)` | toolkit default (300 s) | When the IAM toolkit signs a replacement token in the background. Must be greater than 0 and less than 880 seconds. |

`build()` throws `ElastiCacheIntegrationsConfigError` for a value that is not
usable, for example `cacheRequestTimeoutMs must be a positive number of milliseconds, at most 2147483647`.

### Cache policy overrides

`cached_tables` and `cached_operations` are allowlists, and they intersect:
only those operations, on only those tables. Each one empty or absent means
everything, so the example above caches `Users` and `Sessions` and nothing else.
There is no exclude form. To keep one table out of the cache, name the tables
that should be in it.

Lifetimes are set per category (`item_ttl`, `item_negative_ttl`, `query_ttl`,
`scan_ttl`), connection-wide at the top level or per table under
`table_overrides`. A `table_overrides` entry holds settings only and never puts
a table in scope.

```java
Map<String, Object> overrides = new HashMap<>();
overrides.put("cached_tables", Arrays.asList("Users", "Sessions"));
overrides.put("cached_operations", Arrays.asList("GetItem", "Query"));
overrides.put("item_ttl", 500);
overrides.put("item_negative_ttl", 0); // do not cache "this item does not exist"
overrides.put("table_overrides",
        Collections.singletonMap("Users", Collections.singletonMap("item_ttl", 60)));
```

The cache refuses a value it does not allow at connect time with
`ElastiCacheIntegrationsOverridesRejected`, rather than adjusting it. The cache
ignores a field name it does not recognise, so a typo fails silently. The names
above are the whole set.

## IAM token refresh

`install` creates a toolkit token manager from the region and credentials of
the built SDK client, so the cache is authenticated as the same principal as
the requests. Each newly signed token resolves the client's credentials again,
so rotation (SSO, assume-role, container, instance metadata) keeps working. The
manager caches the current IAM token and refreshes it in the background. The
refresh defaults, accepted range, retry behavior, and safety margin are defined
by the
[toolkit token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/main/java#caching-and-background-refresh).
Set `iamTokenRefreshDelay` on `ElastiCacheIntegrationsClientConfiguration` to
change the background signing point. It must be greater than 0 and less than 880
seconds, which keeps the toolkit's 20-second safety margin on the 15-minute
token.

Cache asks its manager for credentials on every connection and reconnect, so a
reconnect uses the latest valid token without synchronously signing a new one.
If Cache rejects cached IAM credentials during `HELLO 3 AUTH`, the failed
connection is discarded, the manager force-refreshes the token with newly resolved AWS
credentials, and the existing connect or reconnect policy opens the
next connection with the replacement token. Persistent IAM configuration
errors still surface once `connectionTimeoutMs` has passed at startup.
Closing the client also closes the manager, so no additional lifecycle step is
required.

## Reconnection and fallback

Sync and async clients share the same recovery behavior. A disconnect starts
one background reconnect loop immediately, even while the client is idle. Each
attempt creates a new TLS connection, resolves fresh credentials, repeats
`HELLO 3 AUTH` and `aws.config`, and uses capped exponential backoff with jitter.
Reconnect attempts continue until one succeeds or the session closes.

Right after the loss, a cache-eligible request waits up to `connectionTimeoutMs`
for the new connection and uses it if it arrives. If the connection is not back
by then, the request uses the signed origin fallback. The same timeout bounds
startup: Cache keeps retrying the first connection until `connectionTimeoutMs`
has passed, caps each attempt by the time left, and then fails client creation
with `ElastiCacheIntegrationsConnectionError`.
Reconnect delays use capped exponential backoff from 100 ms to 3.2 s with 20
percent jitter, and Cache repeats `AUTH` on a live connection every 15 minutes.
These values are not configurable.

`fallbackDurationSeconds` on `ElastiCacheIntegrationsClientConfiguration`
controls how long requests may fall back, counted from the end of the
`connectionTimeoutMs` wait. A negative value (the default) allows fallback
indefinitely. A positive value allows fallback for that many seconds. Zero
never allows fallback, so a request waits up to `connectionTimeoutMs` for a
connection and then raises `ElastiCacheIntegrationsCacheUnavailable`. Once the
window ends, Cache raises a retryable transport failure for the AWS SDK to
handle. With `fallbackDurationSeconds` set to `0`, a routed request waits only
for the part of `connectionTimeoutMs` left in the current outage. Later retries
in the same outage do not restart that wait. If a request already admitted to Cache fails
as the connection drops, it falls back directly only when its operation is in
the shared replay-safe catalog. On a healthy connection, an `ORIGIN_ERROR` is
SDK-retryable only for a cataloged operation; other RESP errors and
non-idempotent failures are terminal for that attempt.

Cache requests may complete out of order when the cache returns a request ID
and later delivers the result in a `aws.response` RESP3 push. Deferred
requests do not block later FIFO replies. If a deferred request times out, only
that request is abandoned; a late push for its ID is dropped. A timeout while a
request is still FIFO-correlated closes the connection to prevent reply
misrouting. A RESP3 push with an unrecognized kind is treated as protocol
corruption: it invalidates the stream and closes the connection, rather than
being ignored.

## Development

Gradle runs on JDK 21 because Error Prone 2.50 requires JDK 21 and
Gradle 8.14 cannot run on JDK 25. The compiler emits Java 8 bytecode
(`options.release = 8`). Tests run on Java 8, 11, 17, and 21. To run them on
another installed JDK, pass `-PtestJavaVersion=<n>`, for example
`./gradlew test -PtestJavaVersion=8`.

`./gradlew build` also runs Spotless with google-java-format
(`./gradlew spotlessApply` fixes formatting), writes JaCoCo reports to
`build/reports/jacoco/test/`, builds the sources and Javadoc JARs, and checks
the JAR contents.

## Releasing

Java releases are built from annotated `vMAJOR.MINOR.PATCH` or
`vMAJOR.MINOR.PATCH-rc.N` tags. The tag version must match `version` in
`build.gradle.kts`.

The `Java Release` workflow tests Java 8, 11, 17, and 21, builds the main,
sources, and Javadocs JARs once, checks their contents, and runs clean Gradle
and Maven consumer smoke tests before the approval-protected `release`
environment signs and publishes them to Maven Central.

Configure these secrets on the `release` GitHub environment:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `MAVEN_SIGNING_KEY` (ASCII-armored private PGP key)
- `MAVEN_SIGNING_PASSWORD`

After Maven Central propagation, the workflow resolves the published version
from clean consumers and attaches the published JARs and POM to the GitHub
release. Publication, propagation verification, and GitHub release creation
are separate jobs so post-publish failures can be retried without attempting
to republish an existing version. A manual workflow run performs the same test,
packaging, and consumer-smoke stages without entering the release environment
or publishing to Maven Central.
