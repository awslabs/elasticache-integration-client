package software.amazon.photon.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTcpProxy;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.Reply;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;
import software.amazon.photon.model.ResolvedConfig;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhotonSession.
 */
class PhotonSessionTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_TOKEN = "test-token";
    private static final long ASYNC_OBSERVATION_MS = 25;

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        server = new MockTlsServer(cache);
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private PhotonConnector createConnection() {
        return new PhotonConnector("localhost", server.getPort(), clientSslContext);
    }

    // === Lifecycle tests ===

    @Test
    void open_transitionsToOpenState() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertEquals(PhotonSession.State.CREATED, session.getState());

        session.open();
        assertEquals(PhotonSession.State.OPEN, session.getState());
        assertTrue(session.isOpen());

        session.close();
    }

    @Test
    void close_transitionsToClosedState() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        assertTrue(session.isOpen());

        session.close();
        assertEquals(PhotonSession.State.CLOSED, session.getState());
        assertFalse(session.isOpen());
    }

    @Test
    void close_isIdempotent() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        session.close();
        session.close(); // Should not throw
        session.close();

        assertEquals(PhotonSession.State.CLOSED, session.getState());
    }

    @Test
    void open_onOpenedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        PhotonError error = assertThrows(PhotonError.class, session::open);
        assertTrue(error.getMessage().contains("open"));

        session.close();
    }

    @Test
    void open_onClosedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        PhotonError error = assertThrows(PhotonError.class, session::open);
        assertTrue(error.getMessage().contains("closed"));
    }

    // === Request tests ===

    @Test
    void request_onOpenSession_succeeds() {
        cache.setReply(Reply.ok(200, Collections.singletonMap("content-type", "application/json"),
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));

        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            PhotonRequest request = new PhotonRequest(
                    "POST",
                    "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.singletonMap("content-type", "application/json"),
                    "{\"TableName\":\"Test\"}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem")
            );

            PhotonResponse response = session.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
        } finally {
            session.close();
        }
    }

    @Test
    void unmatchedRequest_isDeclinedWithoutCacheRoundTrip() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            PhotonResponse response = session.request(new PhotonRequest(
                    "POST",
                    "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(),
                    "{}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "CreateTable")));

            assertNull(response);
            assertTrue(cache.getRequests().isEmpty());
        } finally {
            session.close();
        }
    }

    @Test
    void celAllowlist_routesOnlyAllowlistedTable() {
        cache.setConfigJson("{\"version\":\"1.0\",\"predicates\":[{"
                + "\"expr\":\"service == 'dynamodb' && operation in ops "
                + "&& has(body.TableName) && body.TableName in allowed\","
                + "\"vars\":{\"ops\":[\"GetItem\",\"PutItem\"],"
                + "\"allowed\":[\"Users\"]}}]}");
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            PhotonRequest allowed = new PhotonRequest(
                    "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(),
                    "{\"TableName\":\"Users\"}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "PutItem"));
            PhotonRequest other = new PhotonRequest(
                    "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(),
                    "{\"TableName\":\"Orders\"}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem"));

            assertNotNull(session.request(allowed));
            assertNull(session.request(other));
            assertEquals(1, cache.getRequests().size());
        } finally {
            session.close();
        }
    }

    @Test
    void request_onCreatedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        PhotonError error = assertThrows(PhotonError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("created"));
    }

    @Test
    void request_onClosedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        PhotonError error = assertThrows(PhotonError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("closed"));
    }

    // === Config tests ===

    @Test
    void getConfig_afterOpen_returnsConfig() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertNull(session.getConfig());

        session.open();
        assertNotNull(session.getConfig());
        assertEquals("1.0", session.getConfig().getVersion());

        session.close();
    }

    @Test
    void open_withConfigOverrides_sendsOverrides() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("refresh_interval", 120);

        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN, overrides);
        session.open();

        // Verify the config command was sent
        assertTrue(cache.getReceivedCommands().contains("PHOTON.CONFIG"));

        session.close();
    }

    // === Auth provider tests ===

    @Test
    void open_callsAuthProvider() {
        AtomicInteger authProviderCallCount = new AtomicInteger(0);
        PhotonSession session = new PhotonSession(createConnection(), () -> {
            authProviderCallCount.incrementAndGet();
            return new AuthCredentials(TEST_USER, "dynamic-token");
        });

        session.open();
        assertEquals(1, authProviderCallCount.get());

        session.close();
    }

    @Test
    void open_closesOnAuthFailure() {
        PhotonSession session = new PhotonSession(createConnection(), () -> {
            throw new RuntimeException("Auth provider failed");
        });

        assertThrows(RuntimeException.class, session::open);
        assertEquals(PhotonSession.State.CLOSED, session.getState());
    }

    @Test
    void reconnectsThroughProxyWithFreshCredentials() throws Exception {
        AtomicInteger credentialsCallCount = new AtomicInteger();
        cache.setReply(Reply.ok(200, Collections.emptyMap(), "reconnected"));

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort())) {
            PhotonHandle handle = PhotonHandle.builder()
                    .host("localhost")
                    .port(proxy.getPort())
                    .sslContext(clientSslContext)
                    .authProvider(() -> new AuthCredentials(
                            TEST_USER, "token-" + credentialsCallCount.incrementAndGet()))
                    .reconnectPolicy(fastPolicy())
                    .build();
            try {
                handle.open();
                proxy.disconnectClients();

                try {
                    handle.getSession().request(request());
                } catch (PhotonError expectedFirstRequestFailure) {
                    // A request already admitted to the failed socket is not replayed.
                }

                awaitState(handle.getSession(), PhotonSession.State.OPEN);
                PhotonResponse response = handle.getSession().request(request());
                assertEquals("reconnected",
                        new String(response.getBody(), StandardCharsets.UTF_8));
                assertEquals(2, credentialsCallCount.get());
                assertEquals(2, proxy.getAcceptedConnections());
            } finally {
                handle.close();
            }
        }
    }

    @Test
    void requestFailureStartsRecoveryWithoutBlockingOriginFallback() throws Exception {
        RequestFailingConnector initialConnector = new RequestFailingConnector();
        CountDownLatch recoveryStarted = new CountDownLatch(1);
        CountDownLatch allowRecovery = new CountDownLatch(1);
        BlockingConnectConnector replacementConnector =
                new BlockingConnectConnector(recoveryStarted, allowRecovery);
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : replacementConnector,
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy());
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        try {
            session.open();

            CompletableFuture<?> failedRequest = CompletableFuture.runAsync(
                    () -> session.request(request()), requestExecutor);
            assertThrows(ExecutionException.class,
                    () -> failedRequest.get(1, TimeUnit.SECONDS));
            assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS));
            assertFalse(allowRecovery.await(ASYNC_OBSERVATION_MS, TimeUnit.MILLISECONDS));

            allowRecovery.countDown();
            awaitState(session, PhotonSession.State.OPEN);
            assertEquals(200, session.request(request()).getStatusCode());
            assertEquals(2, connectionCreationCount.get());
        } finally {
            allowRecovery.countDown();
            session.close();
            requestExecutor.shutdownNow();
        }
    }

    @Test
    void fallbackWindowExpiresWhileReconnectContinues() throws Exception {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .fallbackDurationSeconds(0)
                .baseDelayMs(1)
                .maxDelayMs(1)
                .jitterFactor(0)
                .build();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort())) {
            PhotonHandle handle = PhotonHandle.builder()
                    .host("localhost")
                    .port(proxy.getPort())
                    .sslContext(clientSslContext)
                    .authCredentials(TEST_USER, TEST_TOKEN)
                    .connectTimeoutMs(100)
                    .initializationTimeoutMs(100)
                    .reconnectPolicy(policy)
                    .build();
            try {
                handle.open();
                proxy.disableForwardingAndDisconnectClients();
                awaitState(handle.getSession(), PhotonSession.State.RECONNECTING);
                assertThrows(software.amazon.photon.exception.PhotonCacheUnavailable.class,
                        () -> handle.getSession().request(request()));

                proxy.enableForwarding();
                awaitState(handle.getSession(), PhotonSession.State.OPEN);
                assertEquals(200, handle.getSession().request(request()).getStatusCode());
            } finally {
                handle.close();
            }
        }
    }

    @Test
    void unlimitedFallbackDeclinesWhileReconnectKeepsTrying() throws Exception {
        AtomicInteger connectionCreationCount = new AtomicInteger();
        TestConnector initialConnector = new TestConnector();
        PhotonSession session = new PhotonSession(
                () -> connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : new TestConnector(true),
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                ReconnectPolicy.builder()
                        .fallbackDurationSeconds(-1)
                        .baseDelayMs(1)
                        .maxDelayMs(1)
                        .jitterFactor(0)
                        .build());
        try {
            session.open();
            initialConnector.fail();
            assertNull(session.request(request()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (connectionCreationCount.get() < 3 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(connectionCreationCount.get() >= 3);
            assertEquals(PhotonSession.State.RECONNECTING, session.getState());
            assertNull(session.request(request()));
        } finally {
            session.close();
        }
    }

    @Test
    void reconnectReevaluatesRequestWithFreshPredicates() throws Exception {
        TestConnector initialConnector = new TestConnector(false, "true");
        TestConnector replacementConnector = new TestConnector(false, "false");
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : replacementConnector,
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy());
        try {
            session.open();
            initialConnector.fail();

            assertNull(session.request(request()));
            awaitState(session, PhotonSession.State.OPEN);
            assertNull(session.request(request()));
            assertEquals(2, connectionCreationCount.get());
            assertEquals(0, replacementConnector.getRequestCount());
        } finally {
            session.close();
        }
    }

    @Test
    void concurrentCallersShareOneRecoveryFlight() throws Exception {
        TestConnector initialConnector = new TestConnector();
        TestConnector replacementConnector = new TestConnector();
        AtomicInteger connectionCreationCount = new AtomicInteger();
        AtomicInteger credentialsCallCount = new AtomicInteger();
        Supplier<PhotonConnector> connectorFactory = () ->
                connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : replacementConnector;
        PhotonSession session = new PhotonSession(
                connectorFactory,
                () -> new AuthCredentials(
                        TEST_USER, "token-" + credentialsCallCount.incrementAndGet()),
                null,
                fastPolicy());
        ExecutorService requestExecutor = Executors.newFixedThreadPool(8);
        try {
            session.open();
            initialConnector.fail();

            CompletableFuture<?>[] requests = new CompletableFuture<?>[8];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = CompletableFuture.runAsync(
                        () -> session.request(request()),
                        requestExecutor);
            }
            CompletableFuture.allOf(requests).get(5, TimeUnit.SECONDS);

            awaitState(session, PhotonSession.State.OPEN);
            assertEquals(200, session.request(request()).getStatusCode());
            assertEquals(2, connectionCreationCount.get());
            assertEquals(2, credentialsCallCount.get());
        } finally {
            requestExecutor.shutdownNow();
            session.close();
        }
    }

    @Test
    void closeInterruptsReconnectBackoffAndStopsFurtherAttempts() throws Exception {
        TestConnector initialConnector = new TestConnector(false);
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : new TestConnector(true),
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                ReconnectPolicy.builder()
                        .baseDelayMs(1_000)
                        .maxDelayMs(1_000)
                        .jitterFactor(0)
                        .build());
        try {
            session.open();
            initialConnector.fail();
            assertNull(session.request(request()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (connectionCreationCount.get() < 2 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(connectionCreationCount.get() >= 2);

            session.close();
            int attemptsAfterClose = connectionCreationCount.get();
            Thread.sleep(ASYNC_OBSERVATION_MS);
            assertEquals(attemptsAfterClose, connectionCreationCount.get());
            assertEquals(PhotonSession.State.CLOSED, session.getState());
        } finally {
            session.close();
        }
    }

    private static ReconnectPolicy fastPolicy() {
        return ReconnectPolicy.builder()
                .baseDelayMs(1)
                .maxDelayMs(2)
                .jitterFactor(0)
                .build();
    }

    private static PhotonRequest request() {
        return new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem"));
    }

    private static void awaitState(PhotonSession session,
                                   PhotonSession.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, session.getState());
    }

    private static class TestConnector extends PhotonConnector {
        private volatile boolean dead;
        private final boolean failConnect;
        private final String predicateExpression;
        private final AtomicInteger requestCount = new AtomicInteger();

        private TestConnector() {
            this(false);
        }

        private TestConnector(boolean failConnect) {
            this(failConnect, "true");
        }

        private TestConnector(boolean failConnect, String predicateExpression) {
            super("unused", 1, null);
            this.failConnect = failConnect;
            this.predicateExpression = predicateExpression;
        }

        protected void fail() {
            dead = true;
        }

        private int getRequestCount() {
            return requestCount.get();
        }

        @Override
        public void connect() {
            if (failConnect) {
                throw new PhotonConnectionError("injected connection failure");
            }
            dead = false;
        }

        @Override
        public void authenticate(String userId, String authToken) {
            // Test connections accept any credentials without a protocol exchange.
        }

        @Override
        public ResolvedConfig configureAndStartReader(Map<String, Object> configOverrides) {
            Map<String, Object> predicate =
                    Collections.<String, Object>singletonMap("expr", predicateExpression);
            Map<String, Object> rawConfig =
                    Collections.<String, Object>singletonMap(
                            "predicates", Collections.singletonList(predicate));
            return new ResolvedConfig("1.0", 60, rawConfig);
        }

        @Override
        public PhotonResponse request(PhotonRequest request,
                                      PhotonRequestCancellation cancellation) {
            requestCount.incrementAndGet();
            return new PhotonResponse(200, Collections.emptyMap());
        }

        @Override
        public boolean isDead() {
            return dead;
        }

        @Override
        public boolean isReady() {
            return !dead;
        }

        @Override
        public void close() {
            dead = true;
        }
    }

    private static final class RequestFailingConnector extends TestConnector {
        @Override
        public PhotonResponse request(PhotonRequest request,
                                      PhotonRequestCancellation cancellation) {
            fail();
            throw new PhotonConnectionError("injected request failure");
        }
    }

    private static final class BlockingConnectConnector extends TestConnector {
        private final CountDownLatch started;
        private final CountDownLatch proceed;

        private BlockingConnectConnector(CountDownLatch started, CountDownLatch proceed) {
            this.started = started;
            this.proceed = proceed;
        }

        @Override
        public void connect() {
            started.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PhotonConnectionError("interrupted recovery", e);
            }
            super.connect();
        }
    }
}
