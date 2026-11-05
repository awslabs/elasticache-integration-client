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
import java.nio.file.Path;
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
import org.junit.jupiter.api.io.TempDir;
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
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsAuthError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.ReceivedRequest;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.resp.RespArray;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespInteger;
import software.amazon.elasticache.integrations.resp.RespPush;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

class ElastiCacheIntegrationsAsyncClientTest {

    @TempDir Path tempDir;

    private MockCache cache;
    private MockTlsServer server;
    private Path caBundle;
    private SSLContext clientSslContext;
    private final AtomicReference<ElastiCacheIntegrationsAsyncHandle> handle =
            new AtomicReference<>();

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
        caBundle = tempDir.resolve("ca.pem");
        server.writeCaBundle(caBundle);
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void asyncClientWiresUpTheSuppliedClient() throws Exception {
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"from-cache\"]}"));

        try (DynamoDbAsyncClient client =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            assertEquals(
                    Collections.singletonList("from-cache"),
                    client.listTables().get(5, TimeUnit.SECONDS).tableNames());
            assertTrue(handle.get().isOpen());
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

        try (DynamoDbAsyncClient client =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            assertEquals(
                    Collections.emptyList(), client.listTables().get(5, TimeUnit.SECONDS).tableNames());
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

        try (DynamoDbAsyncClient client =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            CompletableFuture<ListTablesResponse> listTables = client.listTables();
            CompletableFuture<DescribeLimitsResponse> describeLimits = client.describeLimits();
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

        try (DynamoDbAsyncClient client =
                newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            CompletableFuture<ListTablesResponse> deferred = client.listTables();
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            CompletableFuture<DescribeLimitsResponse> fast = client.describeLimits();
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
        RecordingAsyncOrigin origin = new RecordingAsyncOrigin();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                DynamoDbAsyncClient client =
                        installAsync(
                                cacheBuilder()
                                        .endpoint("localhost:" + proxy.getPort())
                                        .originAsyncHttpClient(origin),
                                ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
            proxy.disconnectClients();

            awaitRecovered(handle.get().getSession());
            assertEquals(
                    Collections.singletonList("reconnected"),
                    client.listTables().get(5, TimeUnit.SECONDS).tableNames());
            assertEquals(2, cache.getAuthAttempts().size());
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

        try (DynamoDbAsyncClient client =
                installAsync(
                        cacheBuilder()
                                .originAsyncHttpClient(origin)
                                .clientConfiguration(
                                        ElastiCacheIntegrationsClientConfiguration.builder()
                                                .cacheRequestTimeoutMs(100)
                                                .build()),
                        originConfig)) {
            CompletableFuture<ListTablesResponse> timedOut = client.listTables();
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
                    client.listTables().get(5, TimeUnit.SECONDS).tableNames());
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

        try (DynamoDbAsyncClient client =
                newClient(
                        builder -> {
                            sdkConfig(builder);
                            builder.overrideConfiguration(
                                    config -> config.addExecutionInterceptor(callerInterceptor));
                        })) {
            client.listTables().get(5, TimeUnit.SECONDS);
        }

        assertTrue(callerInterceptorInvoked.get());
    }

    @Test
    void asyncClientCanBeInstalledAgainAfterClose() throws Exception {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder();
        sdkConfig(builder);
        cacheBuilder().installAsync(builder).close();
        assertTrue(
                builder.overrideConfiguration().executionInterceptors().isEmpty(),
                "installAsync leaves the application's builder as it was");

        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"from-cache\"]}"));
        try (DynamoDbAsyncClient client = cacheBuilder().installAsync(builder)) {
            assertEquals(
                    Collections.singletonList("from-cache"),
                    client.listTables().get(5, TimeUnit.SECONDS).tableNames());
        }
    }

    @Test
    void asyncClientClosesTheSessionWhenAuthenticationFails() {
        cache.setAuthReplyFunction((userId, token) -> new RespError("WRONGPASS", "rejected"));

        ElastiCacheIntegrationsError error =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () -> newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig));

        assertTrue(error instanceof ElastiCacheIntegrationsAuthError, error.toString());
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
        assertThrows(
                ElastiCacheIntegrationsError.class,
                handle.get().getOwnedAuthProvider()::get,
                "the token manager is closed");
    }

    @Test
    void asyncClientKeepsTheSuppliedBuilderUsableWhenBringupFails() throws Exception {
        AtomicBoolean reject = new AtomicBoolean(true);
        cache.setAuthReplyFunction(
                (userId, token) ->
                        reject.get()
                                ? new RespError("WRONGPASS", "rejected")
                                : new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)));
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder();
        sdkConfig(builder);
        assertThrows(
                ElastiCacheIntegrationsAuthError.class, () -> cacheBuilder().installAsync(builder));

        reject.set(false);
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"from-cache\"]}"));
        try (DynamoDbAsyncClient client = cacheBuilder().installAsync(builder)) {
            assertEquals(
                    Collections.singletonList("from-cache"),
                    client.listTables().get(5, TimeUnit.SECONDS).tableNames());
        }
    }

    @Test
    void asyncClientCloseClosesTheSessionAndKeepsCallerOwnedResources() {
        RecordingAsyncOrigin origin = new RecordingAsyncOrigin();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            DynamoDbAsyncClient client =
                    installAsync(
                            cacheBuilder().originAsyncHttpClient(origin).asyncExecutor(executor),
                            ElastiCacheIntegrationsAsyncClientTest::sdkConfig);
            ManagedIamAuthProvider auth = handle.get().getOwnedAuthProvider();
            assertTrue(handle.get().isOpen());
            assertNotNull(auth.get());

            client.close();

            assertEquals(
                    ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
            assertFalse(handle.get().isOpen());
            assertThrows(ElastiCacheIntegrationsError.class, auth::get);
            assertEquals(0, origin.closeCount.get(), "a caller-supplied origin stays open");
            assertFalse(executor.isShutdown(), "a caller-supplied executor stays open");
            client.close();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void asyncClientRejectsABuilderWithItsOwnHttpClient() {
        RecordingAsyncOrigin ownTransport = new RecordingAsyncOrigin();

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () -> newClient(builder -> sdkConfig(builder.httpClient(ownTransport))));

        assertTrue(error.getMessage().contains("originAsyncHttpClient"), error.getMessage());
        assertNotNull(error.getCause(), "the SDK's own error is the cause");
        assertEquals(0, ownTransport.closeCount.get(), "the application's client stays open");
        assertTrue(cache.getAuthAttempts().isEmpty());
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
    }

    @Test
    void asyncClientRejectsABuilderWithItsOwnHttpClientBuilder() {
        RecordingAsyncOriginBuilder ownTransport = new RecordingAsyncOriginBuilder();
        DynamoDbAsyncClientBuilder builder =
                DynamoDbAsyncClient.builder().httpClientBuilder(ownTransport);
        sdkConfig(builder);

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class, () -> cacheBuilder().installAsync(builder));

        assertTrue(error.getMessage().contains("originAsyncHttpClient"), error.getMessage());
        assertNull(handle.get(), "no cache connection is created");
        builder.build().close();
        assertEquals(1, ownTransport.buildCount.get(), "the application's builder keeps its transport");
    }

    @Test
    void asyncClientRejectsInvalidBodyLimitsBeforeConnecting() {
        ElastiCacheIntegrationsConfigError badBuffer =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () ->
                                installAsync(
                                        cacheBuilder().asyncMaxBufferedRequestBytes(0),
                                        ElastiCacheIntegrationsAsyncClientTest::sdkConfig));
        ElastiCacheIntegrationsConfigError badCollectors =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () ->
                                installAsync(
                                        cacheBuilder().asyncMaxConcurrentBodyCollectors(-1),
                                        ElastiCacheIntegrationsAsyncClientTest::sdkConfig));

        assertTrue(
                badBuffer.getMessage().contains("asyncMaxBufferedRequestBytes"), badBuffer.getMessage());
        assertTrue(
                badCollectors.getMessage().contains("asyncMaxConcurrentBodyCollectors"),
                badCollectors.getMessage());

        assertNull(handle.get(), "no cache connection is created");
    }

    @Test
    void asyncHandleSerializesLifecycleOperations() throws Exception {
        DynamoDbAsyncClient client = newClient(ElastiCacheIntegrationsAsyncClientTest::sdkConfig);
        ElastiCacheIntegrationsAsyncHandle handle = this.handle.get();
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
            client.close();
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
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials("test-user", "test-token")
                        .sslContext(clientSslContext)
                        .buildAsync(
                                DynamoDbAsyncClient.builder(), ElastiCacheIntegrationsAsyncClientTest::sdkConfig)) {
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

    private ElastiCacheIntegrationsClient.Builder cacheBuilder() {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("localhost:" + server.getPort())
                .cacheName("my-cache")
                .caCerts(caBundle)
                .asyncHandleObserver(handle::set);
    }

    private DynamoDbAsyncClient newClient(Consumer<DynamoDbAsyncClientBuilder> configurer) {
        return installAsync(cacheBuilder(), configurer);
    }

    private static DynamoDbAsyncClient installAsync(
            ElastiCacheIntegrationsClient.Builder cacheBuilder,
            Consumer<DynamoDbAsyncClientBuilder> configurer) {
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder();
        configurer.accept(builder);
        return cacheBuilder.installAsync(builder);
    }

    private static Map<String, String> headers(String key, String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/x-amz-json-1.0");
        headers.put(key, value);
        return headers;
    }

    private void awaitRecovered(ElastiCacheIntegrationsSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((cache.getAuthAttempts().size() < 2
                        || session.getState() != ElastiCacheIntegrationsSession.State.OPEN)
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(2, cache.getAuthAttempts().size());
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

    private static final class RecordingAsyncOriginBuilder
            implements SdkAsyncHttpClient.Builder<RecordingAsyncOriginBuilder> {
        private final AtomicInteger buildCount = new AtomicInteger();

        @Override
        public SdkAsyncHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            buildCount.incrementAndGet();
            return new RecordingAsyncOrigin();
        }
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
