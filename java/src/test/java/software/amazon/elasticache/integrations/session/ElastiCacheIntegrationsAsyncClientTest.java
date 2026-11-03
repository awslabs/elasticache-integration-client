package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.DescribeLimitsResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.ReceivedRequest;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.resp.RespArray;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespInteger;
import software.amazon.elasticache.integrations.resp.RespPush;
import software.amazon.elasticache.integrations.resp.RespValue;

class ElastiCacheIntegrationsAsyncClientTest {

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    private static void sdkConfig(DynamoDbAsyncClientBuilder builder) {
        builder
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create("access-key", "secret-key")))
                .endpointOverride(URI.create("http://127.0.0.1:1"));
    }

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        cache.setConfigJson(
                "{\"version\":\"1.0\",\"refresh_interval\":60," + "\"predicates\":[{\"expr\":\"true\"}]}");
        server = new MockTlsServer(cache);
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void asyncOperationIsServedByCache() throws Exception {
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"from-cache\"]}"));

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            assertEquals(
                    Collections.singletonList("from-cache"),
                    result.client().listTables().get(5, TimeUnit.SECONDS).tableNames());
            assertTrue(result.handle().isOpen());
        }

        ReceivedRequest request = cache.getLastRequest();
        assertNotNull(request);
        assertEquals("service=dynamodb&op=ListTables", request.getMetadata());
        assertEquals("{}", new String(request.getBody(), StandardCharsets.UTF_8));
        assertFalse(request.getHeaders().contains("authorization"));
        assertFalse(request.getHeaders().contains("x-amz-date"));
    }

    @Test
    void asyncOperationPreservesSdkRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        cache.setReplyFunction(
                request ->
                        attempts.getAndIncrement() == 0
                                ? Reply.ok(
                                        500,
                                        headers("x-amzn-errortype", "InternalServerError"),
                                        "{\"__type\":\"InternalServerError\",\"message\":\"retry\"}")
                                : Reply.ok(
                                        200,
                                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                                        "{\"TableNames\":[]}"));

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            assertEquals(
                    Collections.emptyList(),
                    result.client().listTables().get(5, TimeUnit.SECONDS).tableNames());
        }

        assertEquals(2, cache.getRequests().size());
    }

    @Test
    void concurrentOperationsKeepPerExecutionMetadata() throws Exception {
        cache.setReplyFunction(
                request -> {
                    if (request.getMetadata().endsWith("ListTables")) {
                        return Reply.ok(
                                200,
                                Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                                "{\"TableNames\":[\"list-tables-response\"]}");
                    }
                    return Reply.ok(
                            200,
                            Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                            "{\"AccountMaxReadCapacityUnits\":123}");
                });

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            CompletableFuture<ListTablesResponse> listTables = result.client().listTables();
            CompletableFuture<DescribeLimitsResponse> describeLimits = result.client().describeLimits();
            CompletableFuture.allOf(listTables, describeLimits).get(5, TimeUnit.SECONDS);

            assertEquals(
                    Collections.singletonList("list-tables-response"), listTables.get().tableNames());
            assertEquals(123L, describeLimits.get().accountMaxReadCapacityUnits());
        }

        Set<String> metadata = new HashSet<>();
        for (ReceivedRequest request : cache.getRequests()) {
            metadata.add(request.getMetadata());
        }
        assertEquals(
                new HashSet<>(
                        Arrays.asList("service=dynamodb&op=ListTables", "service=dynamodb&op=DescribeLimits")),
                metadata);
    }

    @Test
    void deferredAsyncResponseDoesNotBlockLaterAsyncOperation() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch firstReceived = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(
                            200,
                            Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                            "{\"AccountMaxReadCapacityUnits\":123}");
                });

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            CompletableFuture<ListTablesResponse> deferred = result.client().listTables();
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            CompletableFuture<DescribeLimitsResponse> fast = result.client().describeLimits();
            assertEquals(123L, fast.get(5, TimeUnit.SECONDS).accountMaxReadCapacityUnits());
            assertFalse(deferred.isDone());

            server.sendPush(responsePush(42, responseValue("{\"TableNames\":[\"from-deferred-push\"]}")));

            assertEquals(
                    Collections.singletonList("from-deferred-push"),
                    deferred.get(5, TimeUnit.SECONDS).tableNames());
        }
    }

    @Test
    void asyncClientUsesRecoveredConnectionThroughProxy() throws Exception {
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"reconnected\"]}"));
        AtomicInteger credentialsCallCount = new AtomicInteger();
        RecordingAsyncOrigin origin = new RecordingAsyncOrigin();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                        ElastiCacheIntegrationsClient.builder()
                                .endpoint("localhost:" + proxy.getPort())
                                .authProvider(
                                        () ->
                                                new AuthCredentials(
                                                        "test-user", "token-" + credentialsCallCount.incrementAndGet()))
                                .sslContext(clientSslContext)
                                .originAsyncHttpClient(origin)
                                .reconnectPolicy(
                                        ReconnectPolicy.builder()
                                                .fallbackDurationSeconds(-1)
                                                .baseDelayMs(1)
                                                .maxDelayMs(2)
                                                .jitterFactor(0)
                                                .build())
                                .buildAsync(
                                        DynamoDbAsyncClient.builder(),
                                        ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            proxy.disconnectClients();

            awaitRecovered(result.handle().getSession(), credentialsCallCount);
            assertEquals(
                    Collections.singletonList("reconnected"),
                    result.client().listTables().get(5, TimeUnit.SECONDS).tableNames());
            assertEquals(2, credentialsCallCount.get());
            assertEquals(2, proxy.getAcceptedConnections());
        }
    }

    @Test
    void deferredAsyncTimeoutRetriesThroughSdkAndKeepsCacheConnectionUsable() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch firstReceived = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(
                            200,
                            Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                            "{\"TableNames\":[\"still-from-cache\"]}");
                });
        RecordingAsyncOrigin origin = new RecordingAsyncOrigin();

        Consumer<DynamoDbAsyncClientBuilder> originConfig =
                builder ->
                        builder
                                .region(Region.US_EAST_1)
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create("access-key", "secret-key")))
                                .endpointOverride(URI.create("http://localhost:8080"));

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials("test-user", "test-token")
                        .sslContext(clientSslContext)
                        .requestTimeoutMs(100)
                        .originAsyncHttpClient(origin)
                        .buildAsync(DynamoDbAsyncClient.builder(), originConfig)) {
            CompletableFuture<ListTablesResponse> timedOut = result.client().listTables();
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            // The deferred timeout preserves a healthy connection, so ListTables is returned
            // to the SDK as retryable and the SDK's next attempt succeeds through Cache.
            assertEquals(
                    Collections.singletonList("still-from-cache"),
                    timedOut.get(5, TimeUnit.SECONDS).tableNames());
            assertNull(origin.request.get());

            // The request ID was already removed after timeout, so this push is treated as unknown and
            // dropped.
            server.sendPush(responsePush(42, responseValue("{\"TableNames\":[\"late\"]}")));

            assertEquals(
                    Collections.singletonList("still-from-cache"),
                    result.client().listTables().get(5, TimeUnit.SECONDS).tableNames());
        }
    }

    @Test
    void asyncBuilderMergesCallerInterceptor() throws Exception {
        AtomicBoolean callerInterceptorInvoked = new AtomicBoolean();
        ExecutionInterceptor callerInterceptor =
                new ExecutionInterceptor() {
                    @Override
                    public void beforeExecution(
                            Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
                        callerInterceptorInvoked.set(true);
                    }
                };
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[]}"));

        try (ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(
                        builder -> {
                            sdkConfig(builder);
                            builder.overrideConfiguration(
                                    config -> config.addExecutionInterceptor(callerInterceptor));
                        })) {
            result.client().listTables().get(5, TimeUnit.SECONDS);
        }

        assertTrue(callerInterceptorInvoked.get());
    }

    @Test
    void asyncHandleSerializesLifecycleOperations() throws Exception {
        ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> result =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig);
        ElastiCacheIntegrationsAsyncHandle handle = result.handle();
        ExecutorService closers = Executors.newFixedThreadPool(4);

        try {
            assertThrows(ElastiCacheIntegrationsError.class, handle::open);

            CompletableFuture<?>[] closeTasks = new CompletableFuture<?>[8];
            for (int i = 0; i < closeTasks.length; i++) {
                closeTasks[i] = CompletableFuture.runAsync(handle::close, closers);
            }
            CompletableFuture.allOf(closeTasks).get(5, TimeUnit.SECONDS);

            assertFalse(handle.isOpen());
            assertThrows(ElastiCacheIntegrationsError.class, handle::open);
        } finally {
            closers.shutdownNow();
            result.close();
        }
    }

    private ElastiCacheIntegrationsClient.AsyncResult<DynamoDbAsyncClient> newClient(
            Consumer<DynamoDbAsyncClientBuilder> configurer) {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials("test-user", "test-token")
                .sslContext(clientSslContext)
                .buildAsync(DynamoDbAsyncClient.builder(), configurer);
    }

    private static Map<String, String> headers(String key, String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/x-amz-json-1.0");
        headers.put(key, value);
        return headers;
    }

    private static void awaitRecovered(
            ElastiCacheIntegrationsSession session, AtomicInteger credentialsCallCount)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((credentialsCallCount.get() < 2
                        || session.getState() != ElastiCacheIntegrationsSession.State.OPEN)
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(2, credentialsCallCount.get());
        assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
    }

    private static RespPush responsePush(long requestId, RespValue response) {
        return new RespPush(
                Arrays.asList(
                        new RespBulkString(
                                ElastiCacheIntegrationsProtocol.PUSH_KIND_RESPONSE.getBytes(
                                        StandardCharsets.US_ASCII)),
                        new RespBulkString(Long.toString(requestId).getBytes(StandardCharsets.US_ASCII)),
                        response));
    }

    private static RespArray responseValue(String body) {
        return new RespArray(
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString(
                                "content-type:application/x-amz-json-1.0".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(body.getBytes(StandardCharsets.UTF_8))));
    }

    private static final class RecordingAsyncOrigin implements SdkAsyncHttpClient {
        private final AtomicReference<SdkHttpRequest> request = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletableFuture<Void> execute(AsyncExecuteRequest executeRequest) {
            request.set(executeRequest.request());
            CompletableFuture<Void> result = new CompletableFuture<>();
            executeRequest
                    .requestContentPublisher()
                    .subscribe(
                            new Subscriber<ByteBuffer>() {
                                private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

                                @Override
                                public void onSubscribe(Subscription subscription) {
                                    subscription.request(Long.MAX_VALUE);
                                }

                                @Override
                                public void onNext(ByteBuffer value) {
                                    byte[] chunk = new byte[value.remaining()];
                                    value.get(chunk);
                                    bytes.write(chunk, 0, chunk.length);
                                }

                                @Override
                                public void onError(Throwable failure) {
                                    executeRequest.responseHandler().onError(failure);
                                    result.completeExceptionally(failure);
                                }

                                @Override
                                public void onComplete() {
                                    body.set(bytes.toByteArray());
                                    executeRequest
                                            .responseHandler()
                                            .onHeaders(
                                                    SdkHttpFullResponse.builder()
                                                            .statusCode(200)
                                                            .putHeader("content-type", "application/x-amz-json-1.0")
                                                            .build());
                                    executeRequest
                                            .responseHandler()
                                            .onStream(
                                                    subscriber ->
                                                            subscriber.onSubscribe(
                                                                    new Subscription() {
                                                                        private boolean complete;

                                                                        @Override
                                                                        public void request(long count) {
                                                                            if (complete) {
                                                                                return;
                                                                            }
                                                                            complete = true;
                                                                            subscriber.onNext(
                                                                                    ByteBuffer.wrap(
                                                                                            "{\"TableNames\":[]}"
                                                                                                    .getBytes(StandardCharsets.UTF_8)));
                                                                            subscriber.onComplete();
                                                                        }

                                                                        @Override
                                                                        public void cancel() {
                                                                            complete = true;
                                                                        }
                                                                    }));
                                    result.complete(null);
                                }
                            });
            return result;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }
}
