# Photon Java

Photon integration for AWS SDK for Java v2 DynamoDB clients.

Photon connects and authenticates before returning the SDK client. Requests are
sent to Photon when the configured predicates match and otherwise use the
configured DynamoDB origin transport. Predicate declines and non-transport
Photon failures use origin fallback. Connection loss, request timeouts, and an
expired reconnect fallback window are returned to the SDK as retryable
transport failures. SDK retries, response deserialization, and caller-provided
override configuration remain in the normal SDK pipeline.

IAM token generation and refresh are provided by the Amazon ElastiCache
developer toolkit. The toolkit validates and lowercases cache names and user
IDs, resolves fresh AWS credentials for every newly signed token, and owns the
fixed 15-minute SigV4 signing contract. Photon sends the resulting bearer token
only in the TLS-protected `AUTH` exchange; origin request signing remains owned
by the AWS SDK.

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

Expressions that cannot be compiled are ignored. Evaluation errors and
malformed or non-object JSON bodies count as no match. The resolved config is
compiled when the session opens and refreshed and recompiled after every
successful reconnect.

## Synchronous client

```java
try (IamAuthTokenManager iamAuth = IamAuthTokenManager.builder()
                .cacheName("my-cache")
                .userId("my-iam-user")
                .region(Region.US_EAST_1)
                .build();
     PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
        .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
        .authProvider(iamAuth)
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
try (IamAuthTokenManager iamAuth = IamAuthTokenManager.builder()
                .cacheName("my-cache")
                .userId("my-iam-user")
                .region(Region.US_EAST_1)
                .build();
     PhotonClient.AsyncResult<DynamoDbAsyncClient> result = PhotonClient.builder()
        .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
        .authProvider(iamAuth)
        .buildAsync(DynamoDbAsyncClient.builder(), builder -> builder
                .region(Region.US_EAST_1))) {
    CompletableFuture<ListTablesResponse> response =
            result.client().listTables();
}
```

`buildAsync` performs the Photon connection, authentication, and configuration
handshake before returning. DynamoDB operations are asynchronous and do not
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

`IamAuthTokenManager` caches the current IAM token and refreshes it in the
background after five minutes by default. A refresh resolves AWS credentials
again, retries transient signing failures while the current token remains
valid, and reserves the final 20 seconds of the 15-minute token lifetime for
clock skew and reconnect transit time. Configure an earlier refresh with
`refreshAfter(Duration)` when needed.

Photon asks the manager for credentials on every connection and reconnect, so a
reconnect uses the latest valid token without synchronously signing a new one.
Close the manager with the Photon client or handle to stop its daemon refresh
thread. `IamAuthTokenProvider` remains available when every call should sign a
new token and no background lifecycle is desired.

## Reconnection and fallback

Sync and async clients share the same recovery behavior. A disconnect starts
one background reconnect loop immediately, even while the client is idle. Each
attempt creates a new TLS connection, resolves fresh credentials, repeats
`AUTH` and `photon.config`, and uses capped exponential backoff with jitter.
Reconnect attempts continue until one succeeds or the session closes.

Initial startup remains bounded: by default Photon makes three attempts, and
each complete connect, authentication, and configuration attempt is limited to
five seconds.

While reconnecting, cache-eligible requests may use the signed origin fallback.
`fallbackDurationSeconds` controls that window: a negative value allows
fallback indefinitely, zero disables fallback, and a positive value allows
fallback for that many seconds. Once the window ends, Photon raises a retryable
transport failure for the AWS SDK to handle. Requests already admitted to a
failed Photon connection are also returned to the SDK as transport failures;
they are never directly replayed against origin because Photon may have
processed them.

Configure recovery on `PhotonClient`, `PhotonHandle`, or `PhotonAsyncHandle`:

```java
.reconnectPolicy(ReconnectPolicy.builder()
        .fallbackDurationSeconds(30)
        .connectAttempts(3)
        .connectionTimeoutMs(5_000)
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
