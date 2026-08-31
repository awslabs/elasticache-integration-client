# Photon Java

Photon integration for AWS SDK for Java v2 DynamoDB clients.

Photon connects and authenticates before returning the SDK client. Requests are
sent to Photon first and fall back to the configured DynamoDB origin transport
when Photon reports an expected failure. SDK retries, response deserialization,
and caller-provided override configuration remain in the normal SDK pipeline.

## Synchronous client

```java
try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
        .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
        .authProvider(iamAuthProvider)
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
        .authProvider(iamAuthProvider)
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

## Manual lifecycle

`PhotonHandle` and `PhotonAsyncHandle` expose the interceptor and matching HTTP
transport for applications that construct SDK clients manually. Install both
objects from the same handle; using only the interceptor does not route requests
through Photon.

Close the handle and SDK client when they are no longer needed. The factory
result types close both resources and are intended for try-with-resources.
