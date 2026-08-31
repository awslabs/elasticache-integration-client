package software.amazon.photon.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTcpProxy;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.Reply;
import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    private PhotonConnection createConnection() {
        return new PhotonConnection("localhost", server.getPort(), clientSslContext);
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
    void getResolvedConfig_afterOpen_returnsConfig() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertNull(session.getResolvedConfig());

        session.open();
        assertNotNull(session.getResolvedConfig());
        assertEquals("1.0", session.getResolvedConfig().getVersion());

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
        AtomicInteger callCount = new AtomicInteger(0);
        PhotonSession session = new PhotonSession(createConnection(), () -> {
            callCount.incrementAndGet();
            return new AuthCredentials(TEST_USER, "dynamic-token");
        });

        session.open();
        assertEquals(1, callCount.get());

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
        AtomicInteger credentials = new AtomicInteger();
        cache.setReply(Reply.ok(200, Collections.emptyMap(), "reconnected"));

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort())) {
            PhotonHandle handle = PhotonHandle.builder()
                    .host("localhost")
                    .port(proxy.getPort())
                    .sslContext(clientSslContext)
                    .authProvider(() -> new AuthCredentials(
                            TEST_USER, "token-" + credentials.incrementAndGet()))
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

                PhotonResponse response = handle.getSession().request(request());
                assertEquals("reconnected",
                        new String(response.getBody(), StandardCharsets.UTF_8));
                assertEquals(2, credentials.get());
                assertEquals(2, proxy.getAcceptedConnections());
            } finally {
                handle.close();
            }
        }
    }

    @Test
    void circuitOpensAndHalfOpenProbeRestoresConnection() throws Exception {
        ReconnectPolicy policy = ReconnectPolicy.builder()
                .maxReconnectAttempts(1)
                .baseDelayMs(1)
                .maxDelayMs(1)
                .circuitFailureThreshold(1)
                .circuitOpenDurationMs(50)
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
                proxy.setForwarding(false);
                assertThrows(PhotonError.class, () -> handle.getSession().request(request()));
                assertEquals(PhotonSession.CircuitState.OPEN,
                        handle.getSession().getCircuitState());

                int attemptsWhileOpened = proxy.getAcceptedConnections();
                assertThrows(PhotonError.class, () -> handle.getSession().request(request()));
                assertEquals(attemptsWhileOpened, proxy.getAcceptedConnections());

                proxy.setForwarding(true);
                Thread.sleep(75);
                assertEquals(200, handle.getSession().request(request()).getStatusCode());
                assertEquals(PhotonSession.CircuitState.CLOSED,
                        handle.getSession().getCircuitState());
            } finally {
                handle.close();
            }
        }
    }

    @Test
    void concurrentCallersShareOneRecoveryFlight() throws Exception {
        TestConnection initial = new TestConnection();
        TestConnection replacement = new TestConnection();
        AtomicInteger connectionCreations = new AtomicInteger();
        AtomicInteger credentials = new AtomicInteger();
        Supplier<PhotonConnection> connections = () ->
                connectionCreations.getAndIncrement() == 0 ? initial : replacement;
        PhotonSession session = new PhotonSession(
                connections,
                () -> new AuthCredentials(TEST_USER, "token-" + credentials.incrementAndGet()),
                null,
                fastPolicy());
        ExecutorService callers = Executors.newFixedThreadPool(8);
        try {
            session.open();
            initial.fail();

            CompletableFuture<?>[] requests = new CompletableFuture<?>[8];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = CompletableFuture.runAsync(
                        () -> assertEquals(200, session.request(request()).getStatusCode()),
                        callers);
            }
            CompletableFuture.allOf(requests).get(5, TimeUnit.SECONDS);

            assertEquals(2, connectionCreations.get());
            assertEquals(2, credentials.get());
        } finally {
            callers.shutdownNow();
            session.close();
        }
    }

    @Test
    void closeInterruptsReconnectBackoffAndStopsFurtherAttempts() throws Exception {
        TestConnection initial = new TestConnection(false);
        AtomicInteger connectionCreations = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> connectionCreations.getAndIncrement() == 0
                        ? initial : new TestConnection(true),
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                ReconnectPolicy.builder()
                        .maxReconnectAttempts(10)
                        .baseDelayMs(1_000)
                        .maxDelayMs(1_000)
                        .circuitFailureThreshold(1)
                        .circuitOpenDurationMs(1_000)
                        .build());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            session.open();
            initial.fail();
            CompletableFuture<?> request = CompletableFuture.runAsync(
                    () -> session.request(request()), caller);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (connectionCreations.get() < 2 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertTrue(connectionCreations.get() >= 2);

            session.close();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> request.get(1, TimeUnit.SECONDS));
            int attemptsAfterClose = connectionCreations.get();
            Thread.sleep(25);
            assertEquals(attemptsAfterClose, connectionCreations.get());
            assertEquals(PhotonSession.State.CLOSED, session.getState());
        } finally {
            session.close();
            caller.shutdownNow();
        }
    }

    private static ReconnectPolicy fastPolicy() {
        return ReconnectPolicy.builder()
                .maxReconnectAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(2)
                .circuitFailureThreshold(2)
                .circuitOpenDurationMs(50)
                .build();
    }

    private static PhotonRequest request() {
        return new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem"));
    }

    private static final class TestConnection extends PhotonConnection {
        private volatile boolean dead;
        private final boolean failConnect;

        private TestConnection() {
            this(false);
        }

        private TestConnection(boolean failConnect) {
            super("unused", 1, null);
            this.failConnect = failConnect;
        }

        private void fail() {
            dead = true;
        }

        @Override
        public void connect() {
            if (failConnect) {
                throw new software.amazon.photon.exception.PhotonConnectionError(
                        "injected connection failure");
            }
            dead = false;
        }

        @Override
        public void authenticate(String userId, String authToken) {
        }

        @Override
        public software.amazon.photon.model.ResolvedConfig configureAndStartReader(
                Map<String, Object> configOverrides) {
            return new software.amazon.photon.model.ResolvedConfig(
                    "1.0", 60, Collections.emptyMap());
        }

        @Override
        public PhotonResponse request(PhotonRequest request,
                                      software.amazon.photon.connection.PhotonRequestCancellation
                                              cancellation) {
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
}
