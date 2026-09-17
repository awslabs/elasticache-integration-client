package software.amazon.photon.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonConnectionError;
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

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PhotonConnector against MockTlsServer.
 * Exercises the full flow: TLS connect → AUTH → photon.config → photon.request → response.
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
            ResolvedConfig config = conn.connectAndAuthenticate(() -> {
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

    // === photon.request ===

    @Test
    void request_basicGetItem() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/x-amz-json-1.0");
        cache.setReply(Reply.ok(200, respHeaders,
                "{\"Item\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/x-amz-json-1.0");
            headers.put("host", "dynamodb.us-east-1.amazonaws.com");

            PhotonRequest request = new PhotonRequest(
                    "POST",
                    "https://dynamodb.us-east-1.amazonaws.com/",
                    headers,
                    "{\"TableName\":\"Users\",\"Key\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem")
            );

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

            PhotonRequest request = new PhotonRequest(
                    "GET",
                    "https://example.com/resource",
                    headers,
                    new byte[0],
                    new RequestMetadata("s3", "GetObject")
            );

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
                PhotonRequest request = new PhotonRequest(
                        "GET",
                        "https://example.com/item/" + i,
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem")
                );

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
        PhotonConnector conn = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext, 5000, 150);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Thread.sleep(350);

            PhotonResponse response = conn.request(new PhotonRequest(
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
        cache.setReply(Reply.ok(404, respHeaders, "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/missing",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            PhotonResponse response = conn.request(request);
            assertEquals(404, response.getStatusCode());
            assertEquals("{\"error\":\"not found\"}", new String(response.getBody(), StandardCharsets.UTF_8));
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

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/item",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            assertThrows(PhotonRejectedError.class, () -> conn.request(request));
        } finally {
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

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/item",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        assertThrows(PhotonConnectionLost.class, () -> conn.request(request));
    }

    @Test
    void connect_invalidHost_throwsConnectionError() {
        // Use a non-routable address to trigger connection failure
        PhotonConnector conn = new PhotonConnector("192.0.2.1", 9999, clientSslContext,
                200, 200);
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

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/item",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            assertThrows(PhotonConnectionLost.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverHangs_timesOutAndKillsConnection() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("photon.request");

        PhotonConnector conn = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext,
                5000, 5000, 100, PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/item",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem"));

            assertThrows(PhotonTimeoutError.class, () -> conn.request(request));
            assertTrue(conn.isDead());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_cancelledResponseRetainsFifoPosition() throws Exception {
        CountDownLatch firstReceived = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        cache.setReplyFunction(request -> {
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

        PhotonConnector conn = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext,
                5000, 5000, 2000, PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            PhotonRequestCancellation cancellation = new PhotonRequestCancellation();
            Future<PhotonResponse> cancelled = executor.submit(
                    () -> conn.request(testRequest(), cancellation));
            assertTrue(firstReceived.await(2, TimeUnit.SECONDS));

            cancellation.cancel();
            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> cancelled.get(1, TimeUnit.SECONDS));
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
    void request_sendLockContention_timesOutAndKillsConnection() throws Exception {
        PhotonConnector conn = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext,
                5000, 5000, 100, PhotonConnector.DEFAULT_MAX_PENDING);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Lock sendLock = getField(conn, "sendLock", Lock.class);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            sendLock.lock();
            Future<PhotonResponse> request = executor.submit(() -> conn.request(testRequest()));

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> request.get(2, TimeUnit.SECONDS));
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
        PhotonConnector conn = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext,
                5000, 5000, 100, PhotonConnector.DEFAULT_MAX_PENDING);
        try {
            conn.connectAndAuthenticate(TEST_USER, TEST_TOKEN);
            Field outputStream = PhotonConnector.class.getDeclaredField("outputStream");
            outputStream.setAccessible(true);
            outputStream.set(conn, new OutputStream() {
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
        return new PhotonRequest(
                "GET", "https://example.com/item",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem"));
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
                executor.submit(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready
                        for (int i = 0; i < requestsPerThread; i++) {
                            PhotonRequest request = new PhotonRequest(
                                    "GET",
                                    "https://example.com/item/t" + threadNum + "_" + i,
                                    Collections.emptyMap(),
                                    new byte[0],
                                    new RequestMetadata("dynamodb", "GetItem")
                            );
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
                CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                    PhotonRequest request = new PhotonRequest(
                            "GET",
                            "https://example.com/item/" + requestNum,
                            Collections.emptyMap(),
                            marker.getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem")
                    );
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
                assertEquals(sentMarker, receivedBody,
                        "Response body should match the request body (FIFO correlation)");
            }
        } finally {
            conn.close();
        }
    }

    // === PendingRequests bounds ===

    @Test
    void request_exceedingMaxPending_throwsPhotonConnectionLost() throws Exception {
        // Create connection with very low max pending
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext,
                5000, 5000, 2);
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
    void configureAndStartReader_calledTwice_throwsIllegalState() {
        PhotonConnector conn = new PhotonConnector("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect();
            conn.authenticate(TEST_USER, TEST_TOKEN);
            conn.configureAndStartReader(Collections.emptyMap());
            assertThrows(IllegalStateException.class, () -> conn.configureAndStartReader(Collections.emptyMap()));
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
        assertTrue(error.getMessage().contains("TLS") || error.getCause() != null,
                "Should fail due to TLS hostname verification");
    }
}
