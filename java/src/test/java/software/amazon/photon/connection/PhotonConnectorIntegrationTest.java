package software.amazon.photon.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonAuthenticationError;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.exception.PhotonRejectedError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.ReceivedRequest;
import software.amazon.photon.mock.Reply;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.resp.RespArray;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespError;
import software.amazon.photon.resp.RespInteger;
import software.amazon.photon.resp.RespPush;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

/**
 * Integration tests for PhotonConnector against MockTlsServer. Exercises the full flow: TLS connect
 * → AUTH → photon.config → photon.request → response.
 */
class PhotonConnectorIntegrationTest {

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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            // Step 1: TCP + TLS
            conn.connect();
            // Connection established but not authenticated
            assertTrue(conn.isConnected());
            assertNull(conn.getConfig());

            // Step 2: AUTH
            conn.authenticate(TEST_USER, "my-auth-token");
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, "secret");
            // The mock should have received AUTH and photon.config
            assertTrue(cache.getReceivedCommands().size() >= 2);
            assertEquals("AUTH", cache.getReceivedCommands().get(0));
            assertEquals("PHOTON.CONFIG", cache.getReceivedCommands().get(1));
        } finally {
            conn.close();
        }
    }

    @Test
    void connectAndAuthenticate_withConfigOverrides() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("refresh_interval", 120);
            ResolvedConfig config = conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN, overrides);
            assertNotNull(config);
        } finally {
            conn.close();
        }
    }

    @Test
    void connectAndAuthenticate_withNullOverrides_sendsExplicitEmptyObject() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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

    // === photon.request ===

    @Test
    void request_basicGetItem() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/x-amz-json-1.0");
        cache.setReply(
                Reply.ok(
                        200,
                        respHeaders,
                        "{\"Item\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/x-amz-json-1.0");
            headers.put("host", "dynamodb.us-east-1.amazonaws.com");

            PhotonRequest request =
                    new PhotonRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            headers,
                            "{\"TableName\":\"Users\",\"Key\":{\"id\":{\"S\":\"123\"}}}"
                                    .getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem"));

            PhotonResponse response = conn.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().length > 0);
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverReceivesCorrectArgs() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Host", "example.com");
            headers.put("Content-Type", "application/json");
            // These should be excluded
            headers.put("Authorization", "Bearer secret");
            headers.put("X-Amz-Date", "20260723T000000Z");

            PhotonRequest request =
                    new PhotonRequest(
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            for (int i = 0; i < 5; i++) {
                PhotonRequest request =
                        new PhotonRequest(
                                "GET",
                                "https://example.com/item/" + i,
                                Collections.emptyMap(),
                                new byte[0],
                                new RequestMetadata("dynamodb", "GetItem"));

                PhotonResponse response = conn.request(request);
                assertEquals(200, response.getStatusCode());
            }

            // All 5 requests should be recorded (plus AUTH and config)
            assertEquals(5, cache.getRequests().size());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_succeedsAfterConnectionIsIdleLongerThanReadTimeout() throws Exception {
        PhotonConnector conn =
                new PhotonConnector("localhost", server.getPort(), clientSslContext, 5000, 150);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Thread.sleep(350);

            PhotonResponse response =
                    conn.request(
                            new PhotonRequest(
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
    void request_customResponseStatus() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/json");
        cache.setReply(
                Reply.ok(404, respHeaders, "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request =
                    new PhotonRequest(
                            "GET",
                            "https://example.com/missing",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            PhotonResponse response = conn.request(request);
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

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request =
                    new PhotonRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(PhotonRejectedError.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void execute_requestIdSimpleStringRemainsFifoReply() {
        cache.setReply(Reply.requestId(42));

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        1000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            RespValue response =
                    conn.execute(
                            "PHOTON.REQUEST",
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

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        2000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<PhotonResponse> deferred =
                    executor.submit(() -> conn.request(testRequest("deferred")));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            Future<PhotonResponse> fast = executor.submit(() -> conn.request(testRequest("fast")));
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
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        2000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<PhotonResponse> deferred = executor.submit(() -> conn.request(testRequest()));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            server.sendPush(responsePush(Long.MAX_VALUE, new RespError("ERR", "origin failed")));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PhotonError);
            assertFalse(failure.getCause() instanceof PhotonProtocolError);
            assertTrue(failure.getCause().getMessage().contains("origin failed"));
            assertFalse(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_unknownPushKind_killsConnection() throws Exception {
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        1000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            server.sendPush(new RespPush(Collections.singletonList(bulk("future.push"))));

            awaitDead(conn);
            assertThrows(PhotonConnectionLost.class, () -> conn.request(testRequest()));
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
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        2000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Future<PhotonResponse> deferred = executor.submit(() -> conn.request(testRequest()));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            server.sendRaw(">2\r\n$15\r\nphoton.response\r\n:42\r\n".getBytes(StandardCharsets.UTF_8));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            // A malformed frame leaves the read position untrustworthy, so the reader thread
            // reports it as a lost connection with the parse failure preserved as the cause.
            assertTrue(failure.getCause() instanceof PhotonConnectionLost);
            assertTrue(failure.getCause().getCause() instanceof PhotonProtocolError);
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
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        150,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Future<PhotonResponse> deferred =
                    executor.submit(() -> conn.request(testRequest("deferred")));
            assertTrue(requestIdSent.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> deferred.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PhotonTimeoutError);
            assertFalse(conn.isDead());

            server.sendPush(responsePush(42, responseValue("late")));
            PhotonResponse response = conn.request(testRequest("after"));

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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        assertTrue(conn.isConnected());

        conn.close();
        assertFalse(conn.isConnected());
    }

    @Test
    void close_isIdempotent() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

        conn.close();
        conn.close(); // Should not throw
        conn.close(); // Should not throw
        assertFalse(conn.isConnected());
    }

    @Test
    void request_afterClose_throwsPhotonConnectionLost() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        conn.close();

        PhotonRequest request =
                new PhotonRequest(
                        "GET",
                        "https://example.com/item",
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem"));

        assertThrows(PhotonConnectionLost.class, () -> conn.request(request));
    }

    @Test
    void connect_invalidHost_throwsConnectionError() {
        // Use a non-routable address to trigger connection failure
        PhotonConnector conn = new PhotonConnector("192.0.2.1", 9999, clientSslContext, 200, 200);
        assertThrows(PhotonConnectionError.class, conn::connect);
    }

    // === Drop failure mode ===

    @Test
    void request_serverDropsConnection_throwsPhotonConnectionLost() {
        cache.setFailureMode(MockCache.FailureMode.DROP);
        cache.setFailureCommand("photon.request");

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request =
                    new PhotonRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(PhotonConnectionLost.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverHangs_timesOutAndKillsConnection() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("photon.request");

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        100,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request =
                    new PhotonRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(PhotonTimeoutError.class, () -> conn.request(request));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    // === Per-command timeout selection ===

    @Test
    void execute_configCommand_usesInitializationTimeoutNotRequestTimeout() {
        // A short initializationTimeoutMs and a long requestTimeoutMs: a hung photon.config sent
        // after bring-up (a refresh tick) must time out on the short budget, proving it is not
        // sharing the request timeout.
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        200,
                        5000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            cache.setFailureMode(MockCache.FailureMode.HANG);
            cache.setFailureCommand("photon.config");

            long startNanos = System.nanoTime();
            assertThrows(PhotonTimeoutError.class, () -> conn.execute(PhotonProtocol.CONFIG_COMMAND));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(
                    elapsedMillis < 2000,
                    "execute(photon.config) took "
                            + elapsedMillis
                            + "ms; expected ~200ms (initializationTimeoutMs), not 5000ms (requestTimeoutMs)");
        } finally {
            conn.close();
        }
    }

    @Test
    void request_usesRequestTimeoutNotInitializationTimeout() {
        // The inverse: a long initializationTimeoutMs and a short requestTimeoutMs. A hung
        // photon.request must time out on the short budget.
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("photon.request");

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        200,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request =
                    new PhotonRequest(
                            "GET",
                            "https://example.com/item",
                            Collections.emptyMap(),
                            new byte[0],
                            new RequestMetadata("dynamodb", "GetItem"));

            long startNanos = System.nanoTime();
            assertThrows(PhotonTimeoutError.class, () -> conn.request(request));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(
                    elapsedMillis < 2000,
                    "request() took "
                            + elapsedMillis
                            + "ms; expected ~200ms (requestTimeoutMs), not 5000ms (initializationTimeoutMs)");
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

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        2000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            PhotonRequestCancellation cancellation = new PhotonRequestCancellation();
            Future<PhotonResponse> cancelled =
                    executor.submit(() -> conn.request(testRequest(), cancellation));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            cancellation.cancel();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PhotonRequestCancellation.CancelledException);
            assertFalse(conn.isDead());

            releaseFirst.countDown();
            PhotonResponse next = conn.request(testRequest());
            assertEquals("next", new String(next.getBody(), StandardCharsets.UTF_8));
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

        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        2000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            PhotonRequestCancellation cancellation = new PhotonRequestCancellation();
            Future<PhotonResponse> cancelled =
                    executor.submit(() -> conn.request(testRequest("cancelled"), cancellation));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);

            cancellation.cancel();
            ExecutionException cancellationFailure =
                    assertThrows(ExecutionException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
            assertTrue(
                    cancellationFailure.getCause() instanceof PhotonRequestCancellation.CancelledException);
            assertFalse(conn.isDead());

            Future<PhotonResponse> reusedId = executor.submit(() -> conn.request(testRequest("reused")));
            assertTrue(secondReceived.await(2, TimeUnit.SECONDS));
            awaitFifoSize(conn, 0);
            server.sendPush(responsePush(42, responseValue("reused")));
            assertEquals(
                    "reused",
                    new String(reusedId.get(1, TimeUnit.SECONDS).getBody(), StandardCharsets.UTF_8));

            server.sendPush(responsePush(42, responseValue("late")));
            PhotonResponse after = conn.request(testRequest("after"));
            assertEquals("after", new String(after.getBody(), StandardCharsets.UTF_8));
            assertFalse(conn.isDead());
        } finally {
            executor.shutdownNow();
            conn.close();
        }
    }

    @Test
    void request_sendLockContention_timesOutAndKillsConnection() throws Exception {
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        100,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Lock sendLock = getField(conn, "sendLock", Lock.class);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            sendLock.lock();
            Future<PhotonResponse> request = executor.submit(() -> conn.request(testRequest()));

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PhotonTimeoutError);
            assertTrue(conn.isDead());
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
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        5000,
                        100,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Field outputStream = PhotonConnector.class.getDeclaredField("outputStream");
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

            assertThrows(PhotonTimeoutError.class, () -> conn.request(testRequest()));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    private static PhotonRequest testRequest() {
        return testRequest("");
    }

    private static PhotonRequest testRequest(String body) {
        return new PhotonRequest(
                "GET",
                "https://example.com/item",
                Collections.emptyMap(),
                body.getBytes(StandardCharsets.UTF_8),
                new RequestMetadata("dynamodb", "GetItem"));
    }

    private static RespPush responsePush(long requestId, RespValue response) {
        List<RespValue> elements = new ArrayList<>();
        elements.add(bulk(PhotonProtocol.PUSH_KIND_RESPONSE));
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

    private static void awaitFifoSize(PhotonConnector conn, int expected) throws Exception {
        PendingRequests pending = getField(conn, "pending", PendingRequests.class);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pending.size() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, pending.size());
    }

    private static void awaitDead(PhotonConnector conn) throws InterruptedException {
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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
                executor.submit(
                        () -> {
                            try {
                                startLatch.await(); // Wait for all threads to be ready
                                for (int i = 0; i < requestsPerThread; i++) {
                                    PhotonRequest request =
                                            new PhotonRequest(
                                                    "GET",
                                                    "https://example.com/item/t" + threadNum + "_" + i,
                                                    Collections.emptyMap(),
                                                    new byte[0],
                                                    new RequestMetadata("dynamodb", "GetItem"));
                                    PhotonResponse response = conn.request(request);
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

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            List<CompletableFuture<String>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int requestNum = i;
                final String marker = "marker-" + requestNum;
                CompletableFuture<String> f =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    PhotonRequest request =
                                            new PhotonRequest(
                                                    "GET",
                                                    "https://example.com/item/" + requestNum,
                                                    Collections.emptyMap(),
                                                    marker.getBytes(StandardCharsets.UTF_8),
                                                    new RequestMetadata("dynamodb", "GetItem"));
                                    PhotonResponse response = conn.request(request);
                                    // Return the marker we sent AND the body we got back
                                    String receivedBody = new String(response.getBody(), StandardCharsets.UTF_8);
                                    return marker + "|" + receivedBody;
                                });
                futures.add(f);
            }

            // Wait for all to complete and verify each caller got their own response
            for (int i = 0; i < futures.size(); i++) {
                String result = futures.get(i).get(10, TimeUnit.SECONDS);
                String[] parts = result.split("\\|");
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
    void constructor_acceptsCustomMaxPending() throws Exception {
        // Create connection with very low max pending
        PhotonConnector conn =
                new PhotonConnector("localhost", server.getPort(), clientSslContext, 5000, 5000, 2);
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            assertFalse(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void isDead_trueAfterClose() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
        conn.close();
        assertTrue(conn.isDead());
    }

    // === Error cases ===

    @Test
    void connect_alreadyConnected_throwsIllegalState() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();
            assertThrows(IllegalStateException.class, conn::connect);
        } finally {
            conn.close();
        }
    }

    @Test
    void authenticate_beforeConnect_throwsPhotonConnectionLost() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        assertThrows(PhotonConnectionLost.class, () -> conn.authenticate(TEST_USER, TEST_TOKEN));
    }

    @Test
    void reauthenticate_usesPlainAuthOnTheReadyConnection() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonAuthenticationError error =
                    assertThrows(
                            PhotonAuthenticationError.class,
                            () -> conn.reauthenticate(TEST_USER, "rejected-token"));

            assertTrue(error.getMessage().contains("WRONGPASS"));
            assertTrue(conn.isReady());
            assertEquals(
                    200,
                    conn.request(
                                    new PhotonRequest(
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
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonAuthenticationError error =
                    assertThrows(
                            PhotonAuthenticationError.class, () -> conn.reauthenticate(TEST_USER, "fresh-token"));

            assertTrue(error.getMessage().contains("RespBulkString"));
            assertFalse(error.getMessage().contains("secret-response"));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void reauthenticate_timeoutUsesInitializationBudgetAndClosesConnection() {
        PhotonConnector conn =
                new PhotonConnector(
                        "localhost",
                        server.getPort(),
                        clientSslContext,
                        5000,
                        200,
                        5000,
                        PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            cache.setFailureMode(MockCache.FailureMode.HANG);
            cache.setFailureCommand("AUTH");

            long startNanos = System.nanoTime();
            PhotonConnectionError error =
                    assertThrows(
                            PhotonConnectionError.class, () -> conn.reauthenticate(TEST_USER, "fresh-token"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(error.getMessage().contains("timed out"));
            assertTrue(
                    elapsedMillis < 2000,
                    "reauthenticate() took "
                            + elapsedMillis
                            + "ms; expected ~200ms (initializationTimeoutMs)");
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void configureAndStartReader_calledTwice_throwsIllegalState() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();
            conn.authenticate(TEST_USER, TEST_TOKEN);
            conn.configureAndStartReader(Collections.emptyMap());
            assertThrows(
                    IllegalStateException.class, () -> conn.configureAndStartReader(Collections.emptyMap()));
        } finally {
            conn.close();
        }
    }

    // === TLS hostname verification ===

    @Test
    void connect_withMismatchedHostname_failsHostnameVerification() {
        // The mock server's certificate has CN=localhost
        // Connecting with a different hostname (127.0.0.1) should fail hostname verification
        // even though we trust the certificate (same SSLContext)
        PhotonConnector conn = new PhotonConnector("127.0.0.1", server.getPort(), clientSslContext);

        PhotonConnectionError error = assertThrows(PhotonConnectionError.class, conn::connect);

        // The error should be related to certificate/hostname mismatch
        assertTrue(
                error.getMessage().contains("TLS") || error.getCause() != null,
                "Should fail due to TLS hostname verification");
    }
}
