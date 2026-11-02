package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.ReceivedRequest;
import software.amazon.elasticache.integrations.mock.Reply;

class ElastiCacheIntegrationsClientTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_TOKEN = "test-token";

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    // Common SDK client configuration for tests - fake credentials since we intercept requests
    private final Consumer<DynamoDbClientBuilder> sdkConfig =
            b ->
                    b.region(Region.US_EAST_1)
                            .credentialsProvider(
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(
                                                    "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
                            .endpointOverride(URI.create("http://localhost:8080"));

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
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
    void builder_withEndpoint_parsesHostAndPort() {
        DynamoDbClientBuilder origin = DynamoDbClient.builder();
        sdkConfig.accept(origin);

        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(origin)
                        .build()) {

            assertNotNull(result.client());
            assertNotNull(result.handle());
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void builder_withHostAndPort_buildsSuccessfully() {
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .host("localhost")
                        .port(server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {

            assertNotNull(result.client());
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void clientOperation_isServedByCacheWithoutContactingOrigin() {
        configureListTablesPredicate();
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[]}".getBytes(StandardCharsets.UTF_8)));

        Consumer<DynamoDbClientBuilder> unreachableOriginConfig =
                b ->
                        b.region(Region.US_EAST_1)
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create("access-key", "secret-key")))
                                .endpointOverride(URI.create("http://127.0.0.1:1"));

        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), unreachableOriginConfig)
                        .build()) {
            assertEquals(Collections.emptyList(), result.client().listTables().tableNames());
        }

        ReceivedRequest request = cache.getLastRequest();
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("service=dynamodb&op=ListTables", request.getMetadata());
        assertFalse(request.getHeaders().contains("authorization"));
        assertFalse(request.getHeaders().contains("x-amz-date"));
    }

    @Test
    void clientOperation_reusesCapturedRequestForSdkRetry() {
        configureListTablesPredicate();
        AtomicInteger attempt = new AtomicInteger();
        cache.setReplyFunction(
                request -> {
                    if (attempt.getAndIncrement() == 0) {
                        return Reply.ok(
                                500,
                                mapOf(
                                        "content-type", "application/x-amz-json-1.0",
                                        "x-amzn-errortype", "InternalServerError"),
                                "{\"__type\":\"InternalServerError\",\"message\":\"retry\"}");
                    }
                    return Reply.ok(
                            200,
                            Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                            "{\"TableNames\":[]}");
                });

        Consumer<DynamoDbClientBuilder> unreachableOriginConfig =
                b ->
                        b.region(Region.US_EAST_1)
                                .credentialsProvider(
                                        StaticCredentialsProvider.create(
                                                AwsBasicCredentials.create("access-key", "secret-key")))
                                .endpointOverride(URI.create("http://127.0.0.1:1"));

        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), unreachableOriginConfig)
                        .build()) {
            assertEquals(Collections.emptyList(), result.client().listTables().tableNames());
        }

        assertEquals(2, cache.getRequests().size());
    }

    @Test
    void builder_withEndpointAndHost_throwsElastiCacheIntegrationsError() {
        ElastiCacheIntegrationsClient.Builder builder =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("cache.example.com:6379")
                        .host("other-host")
                        .authCredentials(TEST_USER, TEST_TOKEN);

        assertThrows(
                ElastiCacheIntegrationsError.class,
                () -> builder.origin(DynamoDbClient.builder(), sdkConfig).build());
    }

    @Test
    void builder_withoutEndpointOrHost_throwsElastiCacheIntegrationsError() {
        ElastiCacheIntegrationsClient.Builder builder =
                ElastiCacheIntegrationsClient.builder().authCredentials(TEST_USER, TEST_TOKEN);

        ElastiCacheIntegrationsError error =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () -> builder.origin(DynamoDbClient.builder(), sdkConfig).build());
        assertTrue(error.getMessage().contains("endpoint or host is required"));
    }

    @Test
    void builder_withoutAuthProvider_throwsElastiCacheIntegrationsError() {
        ElastiCacheIntegrationsClient.Builder builder =
                ElastiCacheIntegrationsClient.builder().endpoint("cache.example.com:6379");

        ElastiCacheIntegrationsError error =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () -> builder.origin(DynamoDbClient.builder(), sdkConfig).build());
        assertTrue(error.getMessage().contains("authProvider is required"));
    }

    @Test
    void builder_withStaticAuthToken_usesTokenProvider() {
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, "my-static-token")
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {

            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void result_close_closesBothHandleAndClient() {
        ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build();

        assertTrue(result.handle().isOpen());

        result.close();

        assertFalse(result.handle().isOpen());
    }

    @Test
    void result_close_isIdempotent() {
        ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build();

        result.close();
        result.close(); // Should not throw

        assertFalse(result.handle().isOpen());
    }

    @Test
    void builder_connectionFailure_propagatesWithoutCallingOrigin() throws Exception {
        int cachePort = server.getPort();
        server.close(); // Stop the server so connection fails
        RecordingOriginClient origin = new RecordingOriginClient();

        assertThrows(
                ElastiCacheIntegrationsError.class,
                () ->
                        ElastiCacheIntegrationsClient.builder()
                                .endpoint("localhost:" + cachePort)
                                .authCredentials(TEST_USER, TEST_TOKEN)
                                .sslContext(clientSslContext)
                                .connectTimeoutMs(100)
                                .originHttpClient(origin)
                                .origin(DynamoDbClient.builder(), sdkConfig)
                                .build());

        assertNull(origin.request.get());
        assertEquals(0, origin.closeCount.get(), "caller-managed origin must remain open");
    }

    @Test
    void clientOperation_cacheTimeoutFallsBackToOrigin() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("aws.request");
        RecordingOriginClient origin = new RecordingOriginClient();

        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .requestTimeoutMs(100)
                        .originHttpClient(origin)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {
            assertEquals(Collections.emptyList(), result.client().listTables().tableNames());
        }

        assertNotNull(origin.request.get());
        assertTrue(origin.request.get().httpRequest().firstMatchingHeader("Authorization").isPresent());
    }

    @Test
    void syncClientUsesRecoveredConnectionThroughProxy() throws Exception {
        configureListTablesPredicate();
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        "{\"TableNames\":[\"reconnected\"]}"));
        AtomicInteger credentialsCallCount = new AtomicInteger();
        RecordingOriginClient origin = new RecordingOriginClient();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                        ElastiCacheIntegrationsClient.builder()
                                .endpoint("localhost:" + proxy.getPort())
                                .authProvider(
                                        () ->
                                                new AuthCredentials(
                                                        TEST_USER, "token-" + credentialsCallCount.incrementAndGet()))
                                .sslContext(clientSslContext)
                                .originHttpClient(origin)
                                .reconnectPolicy(
                                        ReconnectPolicy.builder()
                                                .fallbackDurationSeconds(-1)
                                                .baseDelayMs(1)
                                                .maxDelayMs(2)
                                                .jitterFactor(0)
                                                .build())
                                .origin(DynamoDbClient.builder(), sdkConfig)
                                .build()) {
            proxy.disconnectClients();

            awaitRecovered(result.handle().getSession(), credentialsCallCount);
            assertEquals(
                    Collections.singletonList("reconnected"), result.client().listTables().tableNames());
            assertEquals(2, credentialsCallCount.get());
            assertEquals(2, proxy.getAcceptedConnections());
        }
    }

    @Test
    void builder_withOverrides_passesToSession() {
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .overrides(Collections.singletonMap("item_ttl", 300))
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {

            assertNotNull(result.handle().getSession().getConfig());
        }
    }

    @Test
    void builder_withTimeouts_appliesTimeouts() {
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .connectTimeoutMs(1000)
                        .initializationTimeoutMs(2000)
                        .requestTimeoutMs(2000)
                        .sslContext(clientSslContext)
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {

            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void builder_withSdkOverrideConfiguration_mergesWithElastiCacheIntegrationsInterceptor() {
        configureListTablesPredicate();
        // Track if our custom interceptor was installed
        AtomicBoolean customInterceptorCalled = new AtomicBoolean(false);

        ExecutionInterceptor customInterceptor =
                new ExecutionInterceptor() {
                    @Override
                    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attrs) {
                        customInterceptorCalled.set(true);
                    }
                };

        // Create SDK config with custom interceptor and timeout
        ClientOverrideConfiguration sdkOverride =
                ClientOverrideConfiguration.builder()
                        .addExecutionInterceptor(customInterceptor)
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .build();

        // Use sdkConfigurer to set override configuration - Cache should merge with it
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        .origin(
                                DynamoDbClient.builder(),
                                b -> {
                                    sdkConfig.accept(b); // Apply base config (region, creds, endpoint)
                                    b.overrideConfiguration(sdkOverride); // Add our custom override config
                                })
                        .build()) {

            assertTrue(result.handle().isOpen());
            result.client().listTables();
            assertTrue(customInterceptorCalled.get());
        }
    }

    @Test
    void builder_withoutSdkOverrideConfiguration_usesDefaultConfig() {
        // This just verifies that when no sdkOverrideConfiguration is set,
        // the build still works (using only the Cache interceptor)
        try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
                ElastiCacheIntegrationsClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .sslContext(clientSslContext)
                        // No sdkOverrideConfiguration set
                        .origin(DynamoDbClient.builder(), sdkConfig)
                        .build()) {

            assertTrue(result.handle().isOpen());
        }
    }

    private static final class RecordingOriginClient implements SdkHttpClient {
        private final AtomicReference<HttpExecuteRequest> request = new AtomicReference<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            this.request.set(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() {
                    return HttpExecuteResponse.builder()
                            .response(
                                    SdkHttpFullResponse.builder()
                                            .statusCode(200)
                                            .putHeader("content-type", "application/x-amz-json-1.0")
                                            .build())
                            .responseBody(
                                    AbortableInputStream.create(
                                            new ByteArrayInputStream(
                                                    "{\"TableNames\":[]}".getBytes(StandardCharsets.UTF_8))))
                            .build();
                }

                @Override
                public void abort() {}
            };
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private void configureListTablesPredicate() {
        cache.setConfigJson(
                "{\"version\":\"1.0\",\"refresh_interval\":60,"
                        + "\"predicates\":[{\"expr\":\"service == 'dynamodb' "
                        + "&& operation == 'ListTables'\",\"vars\":{}}]}");
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

    private static Map<String, String> mapOf(String key1, String value1, String key2, String value2) {
        Map<String, String> values = new HashMap<>();
        values.put(key1, value1);
        values.put(key2, value2);
        return values;
    }
}
