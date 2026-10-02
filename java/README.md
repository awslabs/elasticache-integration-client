# Photon Java

Photon integration for AWS SDK for Java v2 DynamoDB clients.

Photon connects and authenticates before returning the SDK client. Requests are
sent to Photon when the configured predicates match and otherwise use the
configured DynamoDB origin transport. While reconnecting, requests that have
not been sent to Photon use origin fallback until the configured fallback
window expires. After a request is sent, indeterminate failures fall back only
when the connection drops and the operation is in the shared replay-safe
catalog. While the connection remains healthy, an `ORIGIN_ERROR` is returned as
SDK-retryable only for a cataloged operation. `AUTH_ERROR` closes the
stale connection and passes the rejected request through while reconnecting.
Other RESP errors, failures for non-idempotent operations, malformed responses,
an expired fallback window, and shutdown are surfaced without starting an
origin request. SDK retries, response deserialization, and caller-provided
override configuration remain in the normal SDK pipeline.

IAM token generation and refresh are provided by the Amazon ElastiCache
developer toolkit. The toolkit validates and lowercases cache names and user
IDs, resolves fresh AWS credentials for every newly signed token, and owns the
SigV4 signing contract. Photon sends the resulting bearer token only in the
TLS-protected `HELLO 3 AUTH <user> <token>` handshake, which also confirms RESP3
before the connection becomes usable. Photon then periodically repeats plain
`AUTH` on each live connection using the manager's latest token. A rejected
refresh is retried with reconnect backoff while the connection keeps serving
with its last valid authentication state. A timed-out or malformed AUTH reply
drops the connection for FIFO safety and starts normal recovery. Origin request
signing remains owned by the AWS SDK.

## Request routing

Opening a Photon session compiles the CEL predicates returned by
`photon.config`. Each expression can use the request's `service`, `operation`,
`method`, `url`, `headers`, and parsed JSON `body`, plus arbitrary variables
bound by the predicate's `vars` object.

A request is sent to Photon when any predicate evaluates to `true`. Otherwise,
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

The `photon.config` response must include `refresh_interval` as a positive JSON
integer within the Java `Integer` range. Missing, null, fractional, non-positive,
and overflowing values are protocol errors. The session starts a daemon
refresher using this interval. After the initial explicit override map is
acknowledged, refreshes use the zero-argument `photon.config` form to indicate
that the overrides are unchanged. A RESP3 null reply leaves the current config
and interval unchanged; a valid config reply atomically replaces the config and
predicates and sets the next refresh interval.

Periodic refresh pauses while reconnecting, and a late reply from a closed or
replaced connector is ignored. See [Config staleness](#config-staleness) for
what happens when refreshes fail.

## Config staleness

A refresh that fails changes nothing: the last config the server confirmed
stays in force and the next refresh retries. A config the server sends, an
empty "unchanged" reply, and each new connection resolving its own config all
count as confirmation.

`stalenessDurationSeconds` bounds that tolerance. It is a positive whole number
of seconds and defaults to one hour. A refresh that fails past it means the
config can no longer be trusted, so Photon drops the connection, and bringing
up the replacement resolves a fresh config. A refresh that succeeds restarts
the window, so a healthy connection is never dropped. Requests fall back
meanwhile under the configured origin-fallback window. A request already in
flight when the connection drops falls back only when it is idempotent. Set the
duration with `PhotonClient.Builder.stalenessDurationSeconds(long)`.

A `CONFIG_INVALID` reply (the proxy rejected the overrides,
`PhotonOverridesRejected`) and a config with a predicate this client cannot
compile (`PhotonConfigUnevaluable`) never fix themselves, so they log at
`SEVERE` on every occurrence, including each connection attempt. On refresh
they still keep the last good config until the staleness window drops the
connection. Refresh failures that kill the connection start recovery
immediately.

## Synchronous client

```java
try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
        .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
        .authProvider(IamAuthTokenProvider.builder()
                .cacheName("my-cache")
                .userId("my-iam-user")
                .region(Region.US_EAST_1)
                .build())
        .origin(DynamoDbClient.builder(), builder -> builder
                .region(Region.US_EAST_1))
        .build()) {
    ListTablesResponse response = result.client().listTables();
}
```

The default synchronous origin transport is the SDK Apache HTTP client. Supply
a caller-managed transport with `originHttpClient(SdkHttpClient)` when needed.

## Asynchronous client

```java
try (PhotonClient.AsyncResult<DynamoDbAsyncClient> result = PhotonClient.builder()
        .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
        .authProvider(IamAuthTokenProvider.builder()
                .cacheName("my-cache")
                .userId("my-iam-user")
                .region(Region.US_EAST_1)
                .build())
        .buildAsync(DynamoDbAsyncClient.builder(), builder -> builder
                .region(Region.US_EAST_1))) {
    CompletableFuture<ListTablesResponse> response =
            result.client().listTables();
}
```

`buildAsync` performs the Photon connection, RESP3/authentication handshake, and
configuration exchange before returning. DynamoDB operations are asynchronous and do not
block SDK event-loop threads while waiting for Photon.

The default asynchronous origin transport is the SDK Netty HTTP client. Use
`originAsyncHttpClient(SdkAsyncHttpClient)` to supply a caller-managed origin
transport. Photon buffers eligible asynchronous request bodies so the same
signed request can be replayed safely during origin fallback. Only publishers
with a known content length are admitted. Buffering is limited to 16 MiB per
request and eight concurrent collectors by default. Configure these limits with
`asyncMaxBufferedRequestBytes(int)` and
`asyncMaxConcurrentBodyCollectors(int)`. A request with an unknown length, or
one that exceeds either limit, bypasses Photon and uses the origin transport.

Photon's socket request API is blocking, so the asynchronous adapter runs it on
a bounded daemon executor. Applications can supply a caller-managed executor
with `asyncExecutor(Executor)`. If that executor rejects a request because it is
at capacity, Photon sends the buffered request through the origin fallback.
Photon closes only transports and executors it creates itself.

## IAM token refresh

When a concrete `IamAuthTokenProvider` is supplied to `PhotonClient`,
`PhotonHandle`, or `PhotonAsyncHandle`, Photon creates and owns the toolkit
token manager. It caches the current IAM token and refreshes it in the
background. The refresh defaults, accepted range, retry behavior, and safety
margin are defined by the
[toolkit token manager documentation](https://github.com/aws/developer-toolkit-elasticache/tree/release-2/java#caching-and-background-refresh).
Configure a custom refresh point with
`IamAuthTokenProvider.Builder.refreshAfter(Duration)` when needed.

Photon asks its manager for credentials on every connection and reconnect, so a
reconnect uses the latest valid token without synchronously signing a new one.
If Photon rejects cached IAM credentials during `HELLO 3 AUTH`, the failed
connection is discarded, the manager force-refreshes the token with newly resolved AWS
credentials, and the existing bounded connect or reconnect policy opens the
next connection with the replacement token. Persistent IAM configuration
errors still surface after the configured connection-attempt limit.
Closing the Photon result or handle also closes the internally created manager;
no additional lifecycle step is required. Other caller-supplied auth providers
remain caller-owned.

## Reconnection and fallback

Sync and async clients share the same recovery behavior. A disconnect starts
one background reconnect loop immediately, even while the client is idle. Each
attempt creates a new TLS connection, resolves fresh credentials, repeats
`HELLO 3 AUTH` and `photon.config`, and uses capped exponential backoff with jitter.
Reconnect attempts continue until one succeeds or the session closes.

Initial startup remains bounded: by default Photon makes three attempts, and
each complete connect, HELLO/authentication, and configuration attempt is limited to
five seconds.

While reconnecting, cache-eligible requests may use the signed origin fallback.
`fallbackDurationSeconds` controls that window: a negative value allows
fallback indefinitely, zero disables fallback, and a positive value allows
fallback for that many seconds. Once the window ends, Photon raises a retryable
transport failure for the AWS SDK to handle. If a request already admitted to
Photon fails as the connection drops, it falls back directly only when its
operation is in the shared replay-safe catalog. On a healthy connection, an
`ORIGIN_ERROR` is SDK-retryable only for a cataloged operation; other RESP
errors and non-idempotent failures are terminal for that attempt.

Configure recovery on `PhotonClient`, `PhotonHandle`, or `PhotonAsyncHandle`:

```java
.reconnectPolicy(ReconnectPolicy.builder()
        .fallbackDurationSeconds(30)
        .connectAttempts(3)
        .connectionTimeoutMs(5_000)
        .authRefreshIntervalSeconds(900)
        .baseDelayMs(100)
        .maxDelayMs(3_200)
        .jitterFactor(0.2)
        .build())
```

Photon requests may complete out of order when the cache returns a request ID
and later delivers the result in a `photon.response` RESP3 push. Deferred
requests do not block later FIFO replies. If a deferred request times out, only
that request is abandoned; a late push for its ID is dropped. A timeout while a
request is still FIFO-correlated closes the connection to prevent reply
misrouting. A RESP3 push with an unrecognized kind is treated as protocol
corruption: it invalidates the stream and closes the connection, rather than
being ignored.

## Manual lifecycle

`PhotonHandle` and `PhotonAsyncHandle` expose the interceptor and matching HTTP
transport for applications that construct SDK clients manually. Install both
objects from the same handle; using only the interceptor does not route requests
through Photon.

Close the handle and SDK client when they are no longer needed. The factory
result types close both resources and are intended for try-with-resources.

## Development

Until the Java toolkit is published to a Maven repository, it is pinned as a
Git submodule and built as part of `./gradlew build`. Initialize it after
cloning with `git submodule update --init --recursive`. Maven must be available
on `PATH` for the sourced toolkit build.

Gradle runs on JDK 21 because Error Prone 2.50 requires JDK 21 and
Gradle 8.14 cannot run on JDK 25. The compiler emits Java 8 bytecode
(`options.release = 8`). Tests run on Java 8, 11, 17, and 21. To run them on
another installed JDK, pass `-PtestJavaVersion=<n>`, for example
`./gradlew test -PtestJavaVersion=8`.

`./gradlew build` also runs Spotless with google-java-format
(`./gradlew spotlessApply` fixes formatting), writes JaCoCo reports to
`build/reports/jacoco/test/`, builds the sources and Javadoc JARs, and checks
the JAR contents.
