package software.amazon.elasticache.integrations.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsAuthError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRequestNotSent;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.ReceivedRequest;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.resp.RespArray;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespInteger;
import software.amazon.elasticache.integrations.resp.RespMap;
import software.amazon.elasticache.integrations.resp.RespPush;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/**
 * Integration tests for ElastiCacheIntegrationsConnector against MockTlsServer. Exercises the full
 * flow: TLS connect → HELLO 3 AUTH → aws.config → aws.request → response.
 */
class ElastiCacheIntegrationsConnectorIntegrationTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_TOKEN = "test-token";

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        server = new MockTlsServer(cache);
        // Trust the mock server's self-signed cert
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    // === Connection + Handshake ===

    @Test
    void connectAndAuthenticate_successfulHandshake() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            ResolvedConfig config = conn.connectAndAuthenticate(TEST_USER, "my-auth-token");
            assertNotNull(config);
            assertEquals("1.0", config.getVersion());
            assertEquals(Integer.valueOf(60), config.getRefreshInterval());
            assertNotNull(config.getRaw());
            assertTrue(conn.isConnected());
        } finally {
            conn.close();
        }
    }

    @Test
    void connect_separateSteps_works() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            // Step 1: TCP + TLS
            conn.connect();
            // Connection established but protocol/auth handshake not complete
            assertTrue(conn.isConnected());
            assertNull(conn.getConfig());

            // Step 2: HELLO 3 with inline AUTH
            conn.handshake(TEST_USER, "my-auth-token");
            assertTrue(conn.isConnected());
            assertNull(conn.getConfig()); // Still no config until step 3

            // Step 3: Configure and start reader
            ResolvedConfig config = conn.configureAndStartReader(Collections.emptyMap());
            assertNotNull(config);
            assertEquals("1.0", config.getVersion());
            assertSame(config, conn.getConfig());
        } finally {
            conn.close();
        }
    }

    @Test
    void connectAndAuthenticate_withSupplier() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        AtomicInteger callCount = new AtomicInteger(0);
        try {
            ResolvedConfig config =
                    conn.connectAndAuthenticate(
                            () -> {
                                callCount.incrementAndGet();
                                return new AuthCredentials(TEST_USER, "provider-token");
                            });
            assertNotNull(config);
            assertEquals(1, callCount.get()); // Provider called exactly once
            assertTrue(conn.isConnected());
        } finally {
            conn.close();
        }
    }

    @Test
    void connect_recordsAuthAndConfig() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, "secret");
            // The mock should have received HELLO and aws.config, with no standalone AUTH.
            assertTrue(cache.getReceivedCommands().size() >= 2);
            assertEquals("HELLO", cache.getReceivedCommands().get(0));
            assertEquals("AWS.CONFIG", cache.getReceivedCommands().get(1));
            assertEquals(
                    Arrays.asList(
                            new RespBulkString("HELLO".getBytes(StandardCharsets.UTF_8)),
                            new RespBulkString("3".getBytes(StandardCharsets.UTF_8)),
                            new RespBulkString("AUTH".getBytes(StandardCharsets.UTF_8)),
                            new RespBulkString(TEST_USER.getBytes(StandardCharsets.UTF_8)),
                            new RespBulkString("secret".getBytes(StandardCharsets.UTF_8))),
                    cache.getReceivedCommandFrames().get(0));
        } finally {
            conn.close();
        }
    }

    @Test
    void connectAndAuthenticate_withConfigOverrides() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("item_ttl", 120);
            ResolvedConfig config = conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN, overrides);
            assertNotNull(config);
        } finally {
            conn.close();
        }
    }

    @Test
    void connectAndAuthenticate_withNullOverrides_sendsExplicitEmptyObject() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            ResolvedConfig config = conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN, null);

            assertNotNull(config);
            assertTrue(
                    cache.wasOverridesSupplied(),
                    "the initial handshake must send {}, not the zero-argument refresh form");
        } finally {
            conn.close();
        }
    }

    // === aws.request ===

    @Test
    void request_basicGetItem() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/x-amz-json-1.0");
        cache.setReply(
                Reply.ok(
                        200,
                        respHeaders,
                        "{\"Item\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8)));

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/x-amz-json-1.0");
            headers.put("host", "dynamodb.us-east-1.amazonaws.com");

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            headers,
                            "{\"TableName\":\"Users\",\"Key\":{\"id\":{\"S\":\"123\"}}}"
                                    .getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem"));

            ElastiCacheIntegrationsResponse response = conn.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().length > 0);
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverReceivesCorrectArgs() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Host", "example.com");
            headers.put("Content-Type", "application/json");
            // These should be excluded
            headers.put("Authorization", "Bearer secret");
            headers.put("X-Amz-Date", "20260723T000000Z");

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/resource",
                            headers,
                            new byte[0],
                            new RequestMetadata("s3", "GetObject"));

            conn.request(request);

            ReceivedRequest received = cache.getLastRequest();
            assertNotNull(received);
            assertEquals("GET", received.getMethod());
            assertEquals("https://example.com/resource", received.getUrl());
            assertEquals("service=s3&op=GetObject", received.getMetadata());

            // Verify excluded headers are not in the normalized form
            String normalizedHeaders = received.getHeaders();
            assertFalse(normalizedHeaders.contains("authorization"));
            assertFalse(normalizedHeaders.contains("x-amz-date"));
            // Included headers should be there (lowercased, sorted)
            assertTrue(normalizedHeaders.contains("content-type:application/json"));
            assertTrue(normalizedHeaders.contains("host:example.com"));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_multipleRequestsOnSameConnection() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            for (int i = 0; i < 5; i++) {
                ElastiCacheIntegrationsRequest request =
                        new ElastiCacheIntegrationsRequest(
                                "GET",
                                "https://example.com/item/" + i,
                                Collections.emptyMap(),
                                new byte[0],
                                new RequestMetadata("dynamodb", "GetItem"));

                ElastiCacheIntegrationsResponse response = conn.request(request);
                assertEquals(200, response.getStatusCode());
            }

            // All 5 requests should be recorded (plus HELLO and config)
            assertEquals(5, cache.getRequests().size());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_succeedsAfterConnectionIsIdleLongerThanReadTimeout() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 150);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Thread.sleep(350);

            ElastiCacheIntegrationsResponse response =
                    conn.request(
                            new ElastiCacheIntegrationsRequest(
                                    "GET",
                                    "https://example.com/item",
                                    Collections.emptyMap(),
                                    new byte[0],
                                    new RequestMetadata("dynamodb", "GetItem")));

            assertEquals(200, response.getStatusCode());
            assertFalse(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_succeedsWithOneByteReadSize() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        ElastiCacheIntegrationsConnector.DEFAULT_CONNECTION_TIMEOUT_MS,
                        ElastiCacheIntegrationsConnector.DEFAULT_REQUEST_TIMEOUT_MS,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING,
                        1);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsResponse response =
                    conn.request(
                            new ElastiCacheIntegrationsRequest(
                                    "GET",
                                    "https://example.com/item",
                                    Collections.emptyMap(),
                                    new byte[0],
                                    new RequestMetadata("dynamodb", "GetItem")));

            assertEquals(200, response.getStatusCode());
        } finally {
            conn.close();
        }
    }

    @Test
    void constructor_rejectsNonPositiveReadSize() {
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new ElastiCacheIntegrationsConnector(
                                        "localhost",
                                        server.getPort(),
                                        clientSslContext,
                                        ElastiCacheIntegrationsConnector.DEFAULT_CONNECTION_TIMEOUT_MS,
                                        ElastiCacheIntegrationsConnector.DEFAULT_REQUEST_TIMEOUT_MS,
                                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING,
                                        0));
        assertEquals("socketReadSize must be positive: 0", e.getMessage());
    }

    @Test
    void request_customResponseStatus() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/json");
        cache.setReply(
                Reply.ok(404, respHeaders, "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)));

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/missing",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            ElastiCacheIntegrationsResponse response = conn.request(request);
            assertEquals(404, response.getStatusCode());
            assertEquals(
                    "{\"error\":\"not found\"}", new String(response.getBody(), StandardCharsets.UTF_8));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_errorReplyThrows() {
        cache.setReply(Reply.error("CACHE_MISS", "item not cached"));

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(ElastiCacheIntegrationsRejectedError.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void execute_requestIdSimpleStringRemainsFifoReply() {
        cache.setReply(Reply.requestId(42));

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        1000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            RespValue response =
                    conn.execute(
                            "AWS.REQUEST",
                            "GET".getBytes(StandardCharsets.UTF_8),
                            "https://example.com/item".getBytes(StandardCharsets.UTF_8),
                            new byte[0],
                            new byte[0],
                            "service=dynamodb&op=GetItem".getBytes(StandardCharsets.UTF_8));

            // If execute allowed deferred responses, +42 would move this request to
            // async tracking. Since no matching push is sent, execute would time out.
            // Reaching this assertion confirms the reply was resolved through FIFO.
            assertEquals(new RespSimpleString("42".getBytes(StandardCharsets.UTF_8)), response);
            assertFalse(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_deferredResponseDoesNotBlockLaterFifoReply() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch firstReceived = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(200, Collections.emptyMap(), request.getBody());
                });

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        2000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> deferred =
                    executor.submit(() -> conn.request(testRequest("deferred")));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            Future<ElastiCacheIntegrationsResponse> fast =
                    executor.submit(() -> conn.request(testRequest("fast")));
            assertEquals(
                    "fast", new String(fast.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
            assertFalse(deferred.isDone());

            server.sendPush(responsePush(42, responseValue("deferred")));
            assertEquals(
                    "deferred",
                    new String(deferred.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_deferredLargeIdAndPushedError_useNormalParserPath() throws Exception {
        CountDownLatch requestIdSent = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    requestIdSent.countDown();
                    return Reply.requestId(Long.MAX_VALUE);
                });
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        2000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> deferred =
                    executor.submit(() -> conn.request(testRequest()));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            server.sendPush(responsePush(Long.MAX_VALUE, new RespError("ERR", "origin failed")));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsError);
            assertFalse(failure.getCause() instanceof ElastiCacheIntegrationsProtocolError);
            assertTrue(failure.getCause().getMessage().contains("origin failed"));
            assertFalse(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_unknownPushKind_killsConnection() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        1000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            server.sendPush(new RespPush(Collections.singletonList(bulk("future.push"))));

            awaitDead(conn);
            assertThrows(ElastiCacheIntegrationsConnectionLost.class, () -> conn.request(testRequest()));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_malformedRecognizedPush_killsConnection() throws Exception {
        CountDownLatch requestIdSent = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    requestIdSent.countDown();
                    return Reply.requestId(42);
                });
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        2000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> deferred =
                    executor.submit(() -> conn.request(testRequest()));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            server.sendRaw(">2\r\n$12\r\naws.response\r\n:42\r\n".getBytes(StandardCharsets.UTF_8));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            // A malformed frame leaves the read position untrustworthy, so the reader thread
            // reports it as a lost connection with the parse failure preserved as the cause.
            assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsConnectionLost);
            assertTrue(failure.getCause().getCause() instanceof ElastiCacheIntegrationsProtocolError);
            assertTrue(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void deeplyNestedReplyFailsCallerAsConnectionLost() throws Exception {
        CountDownLatch requestIdSent = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    requestIdSent.countDown();
                    return Reply.requestId(42);
                });
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        2000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> deferred =
                    executor.submit(() -> conn.request(testRequest()));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            StringBuilder frame = new StringBuilder();
            for (int i = 0; i < 5000; i++) {
                frame.append("*1\r\n");
            }
            frame.append(":1\r\n");
            server.sendRaw(frame.toString().getBytes(StandardCharsets.UTF_8));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsConnectionLost);
            Throwable cause = failure.getCause().getCause();
            assertTrue(cause instanceof ElastiCacheIntegrationsProtocolError);
            assertEquals("RESP nesting exceeds 100 levels", cause.getMessage());
            assertTrue(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_deferredTimeoutKeepsConnectionAliveAndDropsLatePush() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch requestIdSent = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        requestIdSent.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(200, Collections.emptyMap(), request.getBody());
                });
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 150, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Future<ElastiCacheIntegrationsResponse> deferred =
                    executor.submit(() -> conn.request(testRequest("deferred")));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsTimeoutError);
            assertFalse(conn.isDead());

            server.sendPush(responsePush(42, responseValue("late")));
            ElastiCacheIntegrationsResponse response = conn.request(testRequest("after"));

            assertEquals("after", new String(response.getBody(), StandardCharsets.UTF_8));
            assertFalse(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    // === Connection state ===

    @Test
    void close_marksConnectionClosed() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        assertTrue(conn.isConnected());

        conn.close();
        assertFalse(conn.isConnected());
    }

    @Test
    void close_isIdempotent() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

        conn.close();
        conn.close(); // Should not throw
        conn.close(); // Should not throw
        assertFalse(conn.isConnected());
    }

    @Test
    void request_afterClose_throwsElastiCacheIntegrationsConnectionLost() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        conn.close();

        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "GET",
                        "https://example.com/item",
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem"));

        assertThrows(ElastiCacheIntegrationsConnectionLost.class, () -> conn.request(request));
    }

    @Test
    void connect_invalidHost_throwsConnectionError() {
        // Use a non-routable address to trigger connection failure
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("192.0.2.1", 9999, clientSslContext, 200, 200);
        assertThrows(ElastiCacheIntegrationsConnectionError.class, conn::connect);
    }

    // === Drop failure mode ===

    @Test
    void request_serverDropsConnection_throwsElastiCacheIntegrationsConnectionLost() {
        cache.setFailureMode(MockCache.FailureMode.DROP);
        cache.setFailureCommand("aws.request");

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(ElastiCacheIntegrationsConnectionLost.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverHangs_timesOutAndKillsConnection() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("aws.request");

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        100,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(ElastiCacheIntegrationsTimeoutError.class, () -> conn.request(request));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    // === Per-command timeout selection ===

    @Test
    void execute_configCommand_usesRequestTimeoutAfterBringUp() {
        // Once bring-up is complete, a hung aws.config refresh uses the request timeout.
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        200,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            cache.setFailureMode(MockCache.FailureMode.HANG);
            cache.setFailureCommand("aws.config");

            long startNanos = System.nanoTime();
            assertThrows(
                    ElastiCacheIntegrationsTimeoutError.class,
                    () -> conn.execute(ElastiCacheIntegrationsProtocol.CONFIG_COMMAND));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(
                    elapsedMillis < 2000,
                    "execute(aws.config) took " + elapsedMillis + "ms; expected ~200ms (requestTimeoutMs)");
        } finally {
            conn.close();
        }
    }

    @Test
    void request_usesRequestTimeout() {
        // A hung aws.request must time out on the request budget.
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("aws.request");

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        200,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            long startNanos = System.nanoTime();
            assertThrows(ElastiCacheIntegrationsTimeoutError.class, () -> conn.request(request));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(
                    elapsedMillis < 2000,
                    "request() took " + elapsedMillis + "ms; expected ~200ms (requestTimeoutMs)");
        } finally {
            conn.close();
        }
    }

    @Test
    void request_cancelledResponseRetainsFifoPosition() throws Exception {
        CountDownLatch firstReceived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstReceived.countDown();
                        try {
                            releaseFirst.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                        return Reply.ok(200, Collections.emptyMap(), "cancelled");
                    }
                    return Reply.ok(200, Collections.emptyMap(), "next");
                });

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 2000, 1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            ElastiCacheIntegrationsRequestCancellation cancellation =
                    new ElastiCacheIntegrationsRequestCancellation();
            Future<ElastiCacheIntegrationsResponse> cancelled =
                    executor.submit(() -> conn.request(testRequest(), cancellation));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            cancellation.cancel();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
            assertTrue(
                    failure.getCause()
                            instanceof ElastiCacheIntegrationsRequestCancellation.CancelledException);
            assertFalse(conn.isDead());

            Future<ElastiCacheIntegrationsResponse> next =
                    executor.submit(() -> conn.request(testRequest()));
            Thread.sleep(50);
            assertFalse(next.isDone());
            assertEquals(1, cache.getRequests().size());

            releaseFirst.countDown();
            assertEquals(
                    "next", new String(next.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
            assertFalse(conn.isDead());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_cancelledDeferredResponseReleasesIdAndKeepsConnectionUsable() throws Exception {
        CountDownLatch firstReceived = new CountDownLatch(1);
        CountDownLatch secondReceived = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        cache.setReplyFunction(
                request -> {
                    int requestNumber = sequence.getAndIncrement();
                    if (requestNumber == 0) {
                        firstReceived.countDown();
                        return Reply.requestId(42);
                    }
                    if (requestNumber == 1) {
                        secondReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(200, Collections.emptyMap(), request.getBody());
                });

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        2000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            ElastiCacheIntegrationsRequestCancellation cancellation =
                    new ElastiCacheIntegrationsRequestCancellation();
            Future<ElastiCacheIntegrationsResponse> cancelled =
                    executor.submit(() -> conn.request(testRequest("cancelled"), cancellation));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            cancellation.cancel();
            ExecutionException cancellationFailure =
                    assertThrows(ExecutionException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
            assertTrue(
                    cancellationFailure.getCause()
                            instanceof ElastiCacheIntegrationsRequestCancellation.CancelledException);
            assertFalse(conn.isDead());

            Future<ElastiCacheIntegrationsResponse> reusedId =
                    executor.submit(() -> conn.request(testRequest("reused")));
            assertTrue(secondReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);
            server.sendPush(responsePush(42, responseValue("reused")));
            assertEquals(
                    "reused",
                    new String(reusedId.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));

            server.sendPush(responsePush(42, responseValue("late")));
            ElastiCacheIntegrationsResponse after = conn.request(testRequest("after"));
            assertEquals("after", new String(after.getBody(), StandardCharsets.UTF_8));
            assertFalse(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_sendLockContention_timesOutWithoutKillingConnection() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        100,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Lock sendLock = getField(conn, "sendLock", Lock.class);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            sendLock.lock();
            try {
                Future<ElastiCacheIntegrationsResponse> request =
                        executor.submit(() -> conn.request(testRequest()));

                ExecutionException failure =
                        assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsRequestNotSent);
                assertFalse(conn.isDead());
            } finally {
                sendLock.unlock();
            }
            assertEquals(200, conn.request(testRequest()).getStatusCode());
        } finally {
            if (((java.util.concurrent.locks.ReentrantLock) sendLock).isHeldByCurrentThread()) {
                sendLock.unlock();
            }
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_blockedSocketWrite_timesOutAndKillsConnection() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        100,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Field outputStream = ElastiCacheIntegrationsConnector.class.getDeclaredField("outputStream");
            outputStream.setAccessible(true);
            outputStream.set(
                    conn,
                    new OutputStream() {
                        @Override
                        public void write(int value) throws IOException {
                            blockUntilConnectionDies();
                        }

                        @Override
                        public void write(byte[] bytes, int offset, int length) throws IOException {
                            blockUntilConnectionDies();
                        }

                        private void blockUntilConnectionDies() throws IOException {
                            while (!conn.isDead()) {
                                try {
                                    Thread.sleep(5);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(e);
                                }
                            }
                            throw new IOException("socket closed");
                        }
                    });

            assertThrows(ElastiCacheIntegrationsTimeoutError.class, () -> conn.request(testRequest()));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    private static ElastiCacheIntegrationsRequest testRequest() {
        return testRequest("");
    }

    private static ElastiCacheIntegrationsRequest testRequest(String body) {
        return new ElastiCacheIntegrationsRequest(
                "GET",
                "https://example.com/item",
                Collections.emptyMap(),
                body.getBytes(StandardCharsets.UTF_8),
                new RequestMetadata("dynamodb", "GetItem"));
    }

    private static RespPush responsePush(long requestId, RespValue response) {
        List<RespValue> elements = new ArrayList<>();
        elements.add(bulk(ElastiCacheIntegrationsProtocol.PUSH_KIND_RESPONSE));
        elements.add(bulk(Long.toString(requestId)));
        elements.add(response);
        return new RespPush(elements);
    }

    private static RespArray responseValue(String body) {
        return new RespArray(Arrays.asList(new RespInteger(200), bulk(""), bulk(body)));
    }

    private static RespBulkString bulk(String value) {
        return new RespBulkString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitFifoSize(ElastiCacheIntegrationsConnector conn, int expected)
            throws Exception {
        PendingRequests pending = getField(conn, "pending", PendingRequests.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pending.size() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, pending.size());
    }

    private static void awaitDead(ElastiCacheIntegrationsConnector conn) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!conn.isDead() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(conn.isDead());
    }

    private static <T> T getField(Object target, String name, Class<T> type) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

    @Test
    void getConfig_returnsHandshakeConfig() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            ResolvedConfig config = conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            assertEquals(config, conn.getConfig());
        } finally {
            conn.close();
        }
    }

    // === Concurrent requests (new functionality) ===

    @Test
    void request_concurrentFromMultipleThreads() throws Exception {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            int numThreads = 10;
            int requestsPerThread = 5;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int t = 0; t < numThreads; t++) {
                final int threadNum = t;
                executor.execute(
                        () -> {
                            try {
                                startLatch.await(); // Wait for all threads to be ready
                                for (int i = 0; i < requestsPerThread; i++) {
                                    ElastiCacheIntegrationsRequest request =
                                            new ElastiCacheIntegrationsRequest(
                                                    "GET",
                                                    "https://example.com/item/t" + threadNum + "_" + i,
                                                    Collections.emptyMap(),
                                                    new byte[0],
                                                    new RequestMetadata("dynamodb", "GetItem"));
                                    ElastiCacheIntegrationsResponse response = conn.request(request);
                                    if (response.getStatusCode() == 200) {
                                        successCount.incrementAndGet();
                                    }
                                }
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
            }

            startLatch.countDown(); // Release all threads
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads did not complete in time");
            executor.shutdown();

            int totalRequests = numThreads * requestsPerThread;
            assertEquals(totalRequests, successCount.get(), "All requests should succeed");
            assertEquals(0, errorCount.get(), "No errors expected");
            assertEquals(totalRequests, cache.getRequests().size(), "Server should see all requests");
        } finally {
            conn.close();
        }
    }

    @Test
    void request_fifoCorrelation_responsesMatchRequests() throws Exception {
        // Verify FIFO correlation by having the mock echo back a request-specific
        // marker in the response body. Each caller must receive their own marker.
        cache.setReplyFunction(req -> Reply.ok(200, Collections.emptyMap(), req.getBody()));

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int requestNum = i;
                final String marker = "marker-" + requestNum;
                CompletableFuture<String> f =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    ElastiCacheIntegrationsRequest request =
                                            new ElastiCacheIntegrationsRequest(
                                                    "GET",
                                                    "https://example.com/item/" + requestNum,
                                                    Collections.emptyMap(),
                                                    marker.getBytes(StandardCharsets.UTF_8),
                                                    new RequestMetadata("dynamodb", "GetItem"));
                                    ElastiCacheIntegrationsResponse response = conn.request(request);
                                    // Return the marker we sent AND the body we got back
                                    String receivedBody = new String(response.getBody(), StandardCharsets.UTF_8);
                                    return marker + "|" + receivedBody;
                                });
                futures.add(f);
            }

            // Wait for all to complete and verify each caller got their own response
            for (int i = 0; i < futures.size(); i++) {
                String result = futures.get(i).get(10, TimeUnit.SECONDS);
                String[] parts = result.split("\\|", -1);
                assertEquals(2, parts.length, "Result should have marker|body format");
                String sentMarker = parts[0];
                String receivedBody = parts[1];
                assertEquals(
                        sentMarker,
                        receivedBody,
                        "Response body should match the request body (FIFO correlation)");
            }
        } finally {
            conn.close();
        }
    }

    // === PendingRequests bounds ===

    @Test
    void requestsWaitForPendingCapacityInsteadOfFailing() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch firstRequestReceived = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstRequestReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(200, Collections.emptyMap(), request.getBody());
                });

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 2000, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> first =
                    executor.submit(() -> conn.request(testRequest("first")));
            assertTrue(firstRequestReceived.await(1, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            Future<ElastiCacheIntegrationsResponse> second =
                    executor.submit(() -> conn.request(testRequest("second")));
            Thread.sleep(50);
            assertEquals(1, cache.getRequests().size());

            server.sendPush(responsePush(42, responseValue("first")));
            assertEquals(
                    "first", new String(first.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
            assertEquals(
                    "second", new String(second.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
            assertEquals(2, cache.getRequests().size());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void reservedResponseBudgetAfterAdmissionDoesNotSendOrKillConnection() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        CountDownLatch firstRequestReceived = new CountDownLatch(1);
        cache.setReplyFunction(
                request -> {
                    if (sequence.getAndIncrement() == 0) {
                        firstRequestReceived.countDown();
                        return Reply.requestId(42);
                    }
                    return Reply.ok(200, Collections.emptyMap(), request.getBody());
                });

        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 2000, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Lock sendLock = getField(conn, "sendLock", Lock.class);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<ElastiCacheIntegrationsResponse> first =
                    executor.submit(() -> conn.request(testRequest("first")));
            assertTrue(firstRequestReceived.await(1, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            Future<ElastiCacheIntegrationsResponse> second =
                    executor.submit(() -> conn.request(testRequest("second")));
            Thread.sleep(25);
            assertTrue(sendLock.tryLock(1, TimeUnit.SECONDS));
            assertEquals(1, cache.getRequests().size());

            server.sendPush(responsePush(42, responseValue("first")));
            assertEquals(
                    "first", new String(first.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));
            Thread.sleep(1600);
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsRequestNotSent);
            assertEquals(1, cache.getRequests().size());
            assertFalse(conn.isDead());

            sendLock.unlock();
            assertEquals(
                    "third",
                    new String(conn.request(testRequest("third")).getBody(), StandardCharsets.UTF_8));
        } finally {
            if (((java.util.concurrent.locks.ReentrantLock) sendLock).isHeldByCurrentThread()) {
                sendLock.unlock();
            }
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void constructor_acceptsCustomMaxPending() throws Exception {
        // Create connection with very low max pending
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 5000, 5000, 2);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            // We can't easily test this without a slow mock, but we can verify
            // the constructor accepts the parameter
            assertTrue(conn.isConnected());
        } finally {
            conn.close();
        }
    }

    // === Dead connection behavior ===

    @Test
    void isDead_falseWhenConnected() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            assertFalse(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void isDead_trueAfterClose() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        conn.close();
        assertTrue(conn.isDead());
    }

    // === Error cases ===

    @Test
    void connect_alreadyConnected_throwsIllegalState() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();
            assertThrows(IllegalStateException.class, conn::connect);
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_beforeConnect_throwsElastiCacheIntegrationsConnectionLost() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        assertThrows(
                ElastiCacheIntegrationsConnectionLost.class, () -> conn.handshake(TEST_USER, TEST_TOKEN));
    }

    @Test
    void reauthenticate_usesPlainAuthOnTheReadyConnection() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            conn.reauthenticate(TEST_USER, "fresh-token");

            assertEquals(2, cache.getAuthAttempts().size());
            assertEquals("fresh-token", cache.getAuthAttempts().get(1).getToken());
            assertTrue(conn.isReady());
        } finally {
            conn.close();
        }
    }

    @Test
    void reauthenticate_rejectionKeepsLastValidConnectionOpen() {
        AtomicInteger attempts = new AtomicInteger();
        cache.setAuthReplyFunction(
                (userId, token) ->
                        attempts.incrementAndGet() == 1
                                ? new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8))
                                : new RespError("WRONGPASS", "bad token"));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsAuthError error =
                    assertThrows(
                            ElastiCacheIntegrationsAuthError.class,
                            () -> conn.reauthenticate(TEST_USER, "rejected-token"));

            assertTrue(error.getMessage().contains("WRONGPASS"));
            assertTrue(conn.isReady());
            assertEquals(
                    200,
                    conn.request(
                                    new ElastiCacheIntegrationsRequest(
                                            "GET",
                                            "https://example.com/",
                                            Collections.emptyMap(),
                                            new byte[0],
                                            new RequestMetadata("dynamodb", "GetItem")))
                            .getStatusCode());
        } finally {
            conn.close();
        }
    }

    @Test
    void reauthenticate_unexpectedReplyClosesConnectionWithoutLoggingItsValue() {
        AtomicInteger attempts = new AtomicInteger();
        cache.setAuthReplyFunction(
                (userId, token) ->
                        attempts.incrementAndGet() == 1
                                ? new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8))
                                : new RespBulkString("secret-response".getBytes(StandardCharsets.UTF_8)));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            ElastiCacheIntegrationsAuthError error =
                    assertThrows(
                            ElastiCacheIntegrationsAuthError.class,
                            () -> conn.reauthenticate(TEST_USER, "fresh-token"));

            assertTrue(error.getMessage().contains("RespBulkString"));
            assertFalse(error.getMessage().contains("secret-response"));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void reauthenticate_timeoutUsesRequestBudgetAndClosesConnection() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        200,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            cache.setFailureMode(MockCache.FailureMode.HANG);
            cache.setFailureCommand("AUTH");

            long startNanos = System.nanoTime();
            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.reauthenticate(TEST_USER, "fresh-token"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(error.getMessage().contains("timed out"));
            assertTrue(
                    elapsedMillis < 2000,
                    "reauthenticate() took " + elapsedMillis + "ms; expected ~200ms (requestTimeoutMs)");
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void configureAndStartReader_calledTwice_throwsIllegalState() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();
            conn.handshake(TEST_USER, TEST_TOKEN);
            conn.configureAndStartReader(Collections.emptyMap());
            assertThrows(
                    IllegalStateException.class, () -> conn.configureAndStartReader(Collections.emptyMap()));
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_protocolRejected_closesConnectionWithoutExposingToken() {
        cache.setHelloReply(new RespError("NOPROTO", "unsupported protocol version"));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("NOPROTO"));
            assertFalse(error.getMessage().contains(TEST_TOKEN));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_inlineAuthRejected_closesConnectionWithoutExposingToken() {
        cache.setAuthReplyFunction(
                (userId, token) -> new RespError("WRONGPASS", "invalid username-password pair"));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsAuthError error =
                    assertThrows(
                            ElastiCacheIntegrationsAuthError.class, () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("WRONGPASS"));
            assertFalse(error.getMessage().contains(TEST_TOKEN));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_nonMapReply_closesConnection() {
        cache.setHelloReply(new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("expected a map reply"));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_unconfirmedProtocol_closesConnection() {
        Map<RespValue, RespValue> fields = new LinkedHashMap<>();
        fields.put(new RespBulkString("proto".getBytes(StandardCharsets.UTF_8)), new RespInteger(2));
        cache.setHelloReply(new RespMap(fields));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("did not report proto 3"));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_missingProtocol_closesConnection() {
        cache.setHelloReply(new RespMap(Collections.emptyMap()));
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("did not report proto 3"));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_timeout_closesConnectionAndWrapsTimeout() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("HELLO");
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost", server.getPort(), clientSslContext, 100, 5_000, 100);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("HELLO 3 timed out"));
            assertTrue(error.getCause() instanceof ElastiCacheIntegrationsTimeoutError);
            assertFalse(error.getMessage().contains(TEST_TOKEN));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void handshake_connectionDrop_closesConnectionAndWrapsLoss() {
        cache.setFailureMode(MockCache.FailureMode.DROP);
        cache.setFailureCommand("HELLO");
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();

            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));

            assertTrue(error.getMessage().contains("connection lost"));
            assertTrue(error.getCause() instanceof ElastiCacheIntegrationsConnectionLost);
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    // === Connection timeout ===

    @Test
    void handshake_hungHello_timesOutOnConnectionTimeout() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("HELLO");
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        200,
                        5000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connect();

            long startNanos = System.nanoTime();
            ElastiCacheIntegrationsConnectionError error =
                    assertThrows(
                            ElastiCacheIntegrationsConnectionError.class,
                            () -> conn.handshake(TEST_USER, TEST_TOKEN));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(error.getMessage().contains("HELLO 3 timed out"));
            assertTrue(
                    elapsedMillis < 2000,
                    "handshake() took "
                            + elapsedMillis
                            + "ms; expected ~200ms (connectionTimeoutMs), not 5000ms (requestTimeoutMs)");
        } finally {
            conn.close();
        }
    }

    @Test
    void configureAndStartReader_hungInitialConfig_timesOutOnConnectionTimeout() {
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        200,
                        5000,
                        ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connect();
            conn.handshake(TEST_USER, TEST_TOKEN);
            cache.setFailureMode(MockCache.FailureMode.HANG);
            cache.setFailureCommand("aws.config");

            long startNanos = System.nanoTime();
            assertThrows(
                    ElastiCacheIntegrationsConnectionError.class, () -> conn.configureAndStartReader(null));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(
                    elapsedMillis < 2000,
                    "configureAndStartReader() took "
                            + elapsedMillis
                            + "ms; expected ~200ms (connectionTimeoutMs), not 5000ms (requestTimeoutMs)");
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void connect_hungTlsHandshake_timesOutWithinConnectionTimeout() throws Exception {
        // Accepts TCP but never answers the ClientHello, so only the TLS handshake can hang.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            ElastiCacheIntegrationsConnector conn =
                    new ElastiCacheIntegrationsConnector(
                            "localhost",
                            silent.getLocalPort(),
                            clientSslContext,
                            200,
                            5000,
                            ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING);
            try {
                long startNanos = System.nanoTime();
                ElastiCacheIntegrationsConnectionError error =
                        assertThrows(ElastiCacheIntegrationsConnectionError.class, conn::connect);
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                assertEquals(
                        "Timed out connecting to localhost:" + silent.getLocalPort(), error.getMessage());
                assertTrue(
                        elapsedMillis < 2000,
                        "connect() took "
                                + elapsedMillis
                                + "ms; expected ~200ms (connectionTimeoutMs), not 5000ms"
                                + " (requestTimeoutMs)");
            } finally {
                conn.close();
            }
        }
    }

    // === TLS hostname verification ===

    @Test
    void connect_withMismatchedHostname_failsHostnameVerification() {
        // The mock server's certificate has CN=localhost
        // Connecting with a different hostname (127.0.0.1) should fail hostname verification
        // even though we trust the certificate (same SSLContext)
        ElastiCacheIntegrationsConnector conn =
                new ElastiCacheIntegrationsConnector("127.0.0.1", server.getPort(), clientSslContext);

        ElastiCacheIntegrationsConnectionError error =
                assertThrows(ElastiCacheIntegrationsConnectionError.class, conn::connect);

        // A rejected certificate reports as a TLS failure, not as an unreachable cache.
        assertEquals("TLS handshake failed to 127.0.0.1:" + server.getPort(), error.getMessage());
        assertInstanceOf(SSLException.class, error.getCause());
    }
}
