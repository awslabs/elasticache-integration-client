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
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespValue;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    // === Refresher lifecycle tests ===

    /**
     * Captures the refresher a session builds so a test can inspect its thread. The session's
     * package-private createRefresher() seam avoids adding any public constructor or getter.
     */
    private static class RefresherCapturingSession extends PhotonSession {
        private final List<String> teardownOrder;
        private ConfigRefresher captured;

        RefresherCapturingSession(PhotonConnector connector, List<String> teardownOrder) {
            super(connector, TEST_USER, TEST_TOKEN);
            this.teardownOrder = teardownOrder;
        }

        @Override
        ConfigRefresher createRefresher(double intervalSeconds, Runnable refreshOnce) {
            captured = new ConfigRefresher(intervalSeconds, refreshOnce) {
                @Override
                void stop(long timeoutMillis) {
                    if (teardownOrder != null) {
                        teardownOrder.add("refresher.stop");
                    }
                    super.stop(timeoutMillis);
                }
            };
            return captured;
        }
    }

    @Test
    void open_startsRefresherThread() {
        RefresherCapturingSession session = new RefresherCapturingSession(createConnection(), null);
        assertNull(session.captured);

        session.open();
        try {
            assertNotNull(session.captured);
            assertNotNull(session.captured.thread());
            assertTrue(session.captured.thread().isAlive());
            assertEquals("photon-refresher", session.captured.thread().getName());
            assertTrue(session.captured.thread().isDaemon());
        } finally {
            session.close();
        }
    }

    @Test
    void close_stopsRefresherThread() {
        RefresherCapturingSession session = new RefresherCapturingSession(createConnection(), null);
        session.open();
        Thread refresherThread = session.captured.thread();
        assertTrue(refresherThread.isAlive());

        session.close();

        assertFalse(refresherThread.isAlive());
    }

    @Test
    void close_stopsRefresherBeforeClosingConnector() {
        // A refresh must never fire into a half-closed connection during an orderly shutdown.
        List<String> order = new ArrayList<>();
        PhotonConnector connector = new PhotonConnector("localhost", server.getPort(), clientSslContext) {
            @Override
            public void close() {
                order.add("connector.close");
                super.close();
            }
        };

        RefresherCapturingSession session = new RefresherCapturingSession(connector, order);
        session.open();
        session.close();

        assertEquals(Arrays.asList("refresher.stop", "connector.close"), order);
    }

    @Test
    void refresherConstructionFailure_isTerminalAndCleansUpConnector() {
        List<PhotonConnector> created = new ArrayList<>();
        Supplier<PhotonConnector> connectorFactory = () -> {
            PhotonConnector connector = createConnection();
            created.add(connector);
            return connector;
        };
        PhotonSession session = new PhotonSession(connectorFactory,
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN), null, fastPolicy()) {
            @Override
            ConfigRefresher createRefresher(double intervalSeconds, Runnable refreshOnce) {
                throw new IllegalStateException("refresher construction failed");
            }
        };

        assertThrows(IllegalStateException.class, session::open);
        assertEquals(PhotonSession.State.CLOSED, session.getState());
        assertEquals(1, created.size(),
                "refresher construction failure must not retry the connect handshake");
        assertFalse(created.get(0).isReady(), "connector remained open after refresher failure");
        assertFalse(refresherThreadExists(), "a photon-refresher thread leaked from a failed open()");
    }

    @Test
    void refresherStartFailure_isTerminalAndCleansUpConnector() {
        List<PhotonConnector> created = new ArrayList<>();
        Supplier<PhotonConnector> connectorFactory = () -> {
            PhotonConnector connector = createConnection();
            created.add(connector);
            return connector;
        };
        PhotonSession session = new PhotonSession(connectorFactory,
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN), null, fastPolicy()) {
            @Override
            ConfigRefresher createRefresher(double intervalSeconds, Runnable refreshOnce) {
                return new ConfigRefresher(intervalSeconds, refreshOnce) {
                    @Override
                    void start() {
                        throw new RuntimeException("refresher start failed");
                    }
                };
            }
        };

        assertThrows(RuntimeException.class, session::open);
        assertEquals(PhotonSession.State.CLOSED, session.getState());
        assertEquals(1, created.size(),
                "refresher start failure must not retry the connect handshake");
        assertFalse(created.get(0).isReady(), "connector remained open after refresher failure");
        assertFalse(refresherThreadExists(), "a photon-refresher thread leaked from a failed open()");
    }

    @Test
    void close_duringConcurrentOpen_doesNotLeakRefresherThread() throws Exception {
        // Credential resolution is application code and may block indefinitely. close() must still
        // win the lifecycle, close the connector, and prevent open() from starting a refresher.
        CountDownLatch authProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseAuthProvider = new CountDownLatch(1);
        PhotonSession blockingSession = new PhotonSession(createConnection(), () -> {
            authProviderEntered.countDown();
            try {
                releaseAuthProvider.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new AuthCredentials(TEST_USER, TEST_TOKEN);
        });

        // Daemon so a reintroduced deadlock hangs neither the JVM shutdown nor the test runner;
        // capture uncaught exceptions so a thread dying silently can't produce a false pass.
        List<Throwable> openerFailure = new ArrayList<>();
        List<Throwable> closerFailure = new ArrayList<>();
        Thread opener = new Thread(blockingSession::open);
        opener.setDaemon(true);
        opener.setUncaughtExceptionHandler((t, e) -> openerFailure.add(e));
        Thread closer = new Thread(blockingSession::close);
        closer.setDaemon(true);
        closer.setUncaughtExceptionHandler((t, e) -> closerFailure.add(e));

        opener.start();
        try {
            assertTrue(authProviderEntered.await(5, TimeUnit.SECONDS));

            closer.start();
            closer.join(5000);
            assertFalse(closer.isAlive(), "close() blocked behind an in-flight open()");
            assertTrue(closerFailure.isEmpty(), "close() threw unexpectedly: " + closerFailure);
            assertEquals(PhotonSession.State.CLOSED, blockingSession.getState());
        } finally {
            if (closer.getState() == Thread.State.NEW) {
                closer.start();
            }
            releaseAuthProvider.countDown();
            opener.join(5000);
            closer.join(5000);
        }

        // A timeout above leaves the thread alive (join() with a timeout does not fail by itself),
        // so assert completion explicitly rather than letting a hung thread slide into the state
        // assertions below and produce a false pass.
        assertFalse(opener.isAlive(), "open() did not complete within timeout; likely deadlocked");
        assertEquals(1, openerFailure.size(), "open() should fail after close() wins the lifecycle");

        assertEquals(PhotonSession.State.CLOSED, blockingSession.getState());
        assertFalse(refresherThreadExists(), "a photon-refresher thread leaked from a close() racing open()");
    }

    /** True if any live thread is named "photon-refresher". */
    private static boolean refresherThreadExists() {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int found = Thread.enumerate(threads);
        for (int i = 0; i < found; i++) {
            if (threads[i] != null && "photon-refresher".equals(threads[i].getName())
                    && threads[i].isAlive()) {
                return true;
            }
        }
        return false;
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
        cache.setConfigJson("{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[{"
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
    void reconnectRetriesWhenCandidateDiesBeforePublication() throws Exception {
        TestConnector initialConnector = new TestConnector();
        DiesBeforePublishConnector dyingReplacement = new DiesBeforePublishConnector();
        TestConnector stableReplacement = new TestConnector();
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> {
                    int connection = connectionCreationCount.getAndIncrement();
                    if (connection == 0) {
                        return initialConnector;
                    }
                    return connection == 1 ? dyingReplacement : stableReplacement;
                },
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy());
        try {
            session.open();
            initialConnector.fail();

            assertNull(session.request(request()));
            awaitState(session, PhotonSession.State.OPEN);

            assertEquals(200, session.request(request()).getStatusCode());
            assertEquals(3, connectionCreationCount.get());
            assertTrue(dyingReplacement.isDead());
        } finally {
            session.close();
        }
    }

    @Test
    void reconnectRetriesNonPhotonConfigFailureWithoutPublishingPartialState()
            throws Exception {
        TestConnector initialConnector = new TestConnector(false, "true");
        FailingConfigConnector failingReplacement = new FailingConfigConnector();
        CountDownLatch stableStarted = new CountDownLatch(1);
        CountDownLatch allowStable = new CountDownLatch(1);
        BlockingConnectConnector stableReplacement =
                new BlockingConnectConnector(stableStarted, allowStable, "false");
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> {
                    int connection = connectionCreationCount.getAndIncrement();
                    if (connection == 0) {
                        return initialConnector;
                    }
                    return connection == 1 ? failingReplacement : stableReplacement;
                },
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy());
        try {
            session.open();
            ResolvedConfig initialConfig = session.getConfig();
            initialConnector.fail();

            assertNull(session.request(request()));
            assertTrue(stableStarted.await(1, TimeUnit.SECONDS));
            assertEquals(PhotonSession.State.RECONNECTING, session.getState());
            assertSame(initialConfig, session.getConfig());
            assertTrue(failingReplacement.isDead());

            allowStable.countDown();
            awaitState(session, PhotonSession.State.OPEN);
            assertNull(session.request(request()));
            assertEquals(3, connectionCreationCount.get());
        } finally {
            allowStable.countDown();
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

    private static final class DiesBeforePublishConnector extends TestConnector {
        private boolean firstReadinessCheck = true;

        @Override
        public boolean isReady() {
            if (firstReadinessCheck) {
                firstReadinessCheck = false;
                fail();
            }
            return super.isReady();
        }
    }

    private static final class FailingConfigConnector extends TestConnector {
        @Override
        public ResolvedConfig configureAndStartReader(Map<String, Object> configOverrides) {
            throw new IllegalStateException("injected non-Photon config failure");
        }
    }

    private static final class BlockingConnectConnector extends TestConnector {
        private final CountDownLatch started;
        private final CountDownLatch proceed;

        private BlockingConnectConnector(CountDownLatch started, CountDownLatch proceed) {
            this(started, proceed, "true");
        }

        private BlockingConnectConnector(CountDownLatch started, CountDownLatch proceed,
                                         String predicateExpression) {
            super(false, predicateExpression);
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

    // === Refresh: fetch and apply ===

    @Test
    void refreshOnce_sendsNoArgumentFormAfterBringUp() {
        // Bring-up already sent the overrides; the first tick sends the no-argument form.
        cache.setNoArgConfigReply(null); // RESP3 null: "unchanged"
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN,
                Collections.singletonMap("flag", true));
        try {
            session.open();
            int commandsBeforeTick = cache.getReceivedCommands().size();

            session.refreshOnce();

            assertEquals(commandsBeforeTick + 1, cache.getReceivedCommands().size());
            assertFalse(cache.wasOverridesSupplied(),
                    "the tick after bring-up should send the no-argument form");
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_nullReplyLeavesConfigAndPredicatesUnchanged() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            PhotonRequest matchingRequest = new PhotonRequest(
                    "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(), "{}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem"));
            PhotonRequest nonMatchingRequest = new PhotonRequest(
                    "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(), "{}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "CreateTable"));

            assertNotNull(session.request(matchingRequest));
            assertNull(session.request(nonMatchingRequest));
            int commandsBeforeRefresh = cache.getReceivedCommands().size();

            cache.setNoArgConfigReply(null); // Explicitly return RESP3 null.
            session.refreshOnce();

            assertEquals(commandsBeforeRefresh + 1, cache.getReceivedCommands().size());
            assertSame(originalConfig, session.getConfig());
            assertNotNull(session.request(matchingRequest));
            assertNull(session.request(nonMatchingRequest));
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_noArgTickStillAppliesAServerInitiatedChange() {
        // Even though the client sent the no-argument form, a resolved-config reply is applied,
        // including its replacement predicate evaluator.
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            PhotonRequest matchingRequest = new PhotonRequest(
                    "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.emptyMap(), "{}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem"));
            assertNotNull(session.request(matchingRequest));
            int commandsBeforeRefresh = cache.getReceivedCommands().size();

            cache.setNoArgConfigReply(
                    "{\"version\":\"2.0\",\"refresh_interval\":45,\"predicates\":[]}");

            session.refreshOnce();

            assertEquals(commandsBeforeRefresh + 1, cache.getReceivedCommands().size());
            assertEquals("2.0", session.getConfig().getVersion());
            assertEquals(Integer.valueOf(45), session.getConfig().getRefreshInterval());
            assertNull(session.request(matchingRequest));
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_withUnacknowledgedOverrides_sendsThemAndAppliesReply() {
        // Simulates a not-yet-reachable "overrides changed" state: once _overridesSent is false,
        // the tick resends the overrides exactly like bring-up.
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN,
                Collections.singletonMap("flag", true));
        try {
            session.open();
            session.setOverridesSent(false);

            session.refreshOnce();

            assertTrue(cache.wasOverridesSupplied());
            assertTrue(session.isOverridesSent());
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_swallowsConnectorErrorAndKeepsCurrentConfig() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setFailureMode(MockCache.FailureMode.DROP);
            cache.setFailureCommand("photon.config");

            assertDoesNotThrow(session::refreshOnce);

            assertSame(originalConfig, session.getConfig());
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_swallowsMalformedReplyAndKeepsCurrentConfig() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval

            assertDoesNotThrow(session::refreshOnce);

            assertSame(originalConfig, session.getConfig());
        } finally {
            session.close();
        }
    }

    @Test
    void unacknowledgedOverrides_areSentAgainAfterAFailedTick() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN,
                Collections.singletonMap("flag", true));
        try {
            session.open();
            session.setOverridesSent(false);
            // The tick with unacknowledged overrides sends the 1-argument form; make that reply
            // malformed so the resend must happen again on the following tick.
            cache.setConfigJson("{\"version\":\"1.0\"}"); // malformed: no refresh_interval

            session.refreshOnce();
            assertFalse(session.isOverridesSent(), "a malformed reply must not confirm the overrides");

            cache.setConfigJson("{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[]}");
            session.refreshOnce();

            assertTrue(cache.wasOverridesSupplied());
            assertTrue(session.isOverridesSent());
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_replyArrivingAfterClose_isIgnored() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseReply = new CountDownLatch(1);
        PhotonConnector connector = new PhotonConnector(
                "localhost", server.getPort(), clientSslContext) {
            @Override
            public RespValue execute(String command, byte[]... args) {
                if (PhotonProtocol.CONFIG_COMMAND.equals(command)) {
                    refreshStarted.countDown();
                    try {
                        if (!releaseReply.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release config reply");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return new RespBulkString((
                            "{\"version\":\"2.0\",\"refresh_interval\":45,\"predicates\":[]}")
                            .getBytes(StandardCharsets.UTF_8));
                }
                return super.execute(command, args);
            }
        };
        PhotonSession session = new PhotonSession(connector, TEST_USER, TEST_TOKEN);
        session.open();
        ResolvedConfig originalConfig = session.getConfig();
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        Thread refreshThread = new Thread(() -> {
            try {
                session.refreshOnce();
            } catch (Throwable failure) {
                refreshFailure.set(failure);
            }
        });

        try {
            refreshThread.start();
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS), "refresh did not start");

            session.close();
            releaseReply.countDown();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(refreshThread.isAlive(), "refresh thread did not finish");
            assertNull(refreshFailure.get());
            assertSame(originalConfig, session.getConfig());
        } finally {
            releaseReply.countDown();
            session.close();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void refreshOnce_replyFromReplacedConnectorIsIgnored() throws Exception {
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseReply = new CountDownLatch(1);
        TestConnector initialConnector = new TestConnector(false, "true") {
            @Override
            public RespValue execute(String command, byte[]... args) {
                refreshStarted.countDown();
                try {
                    if (!releaseReply.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release stale config reply");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new PhotonConnectionError("stale refresh interrupted", e);
                }
                return new RespBulkString((
                        "{\"version\":\"stale\",\"refresh_interval\":90,"
                                + "\"predicates\":[{\"expr\":\"true\"}]}")
                        .getBytes(StandardCharsets.UTF_8));
            }
        };
        TestConnector replacementConnector = new TestConnector(false, "false") {
            @Override
            public ResolvedConfig configureAndStartReader(
                    Map<String, Object> configOverrides) {
                Map<String, Object> predicate =
                        Collections.<String, Object>singletonMap("expr", "false");
                Map<String, Object> rawConfig =
                        Collections.<String, Object>singletonMap(
                                "predicates", Collections.singletonList(predicate));
                return new ResolvedConfig("replacement", 30, rawConfig);
            }
        };
        AtomicInteger connectionCreationCount = new AtomicInteger();
        PhotonSession session = new PhotonSession(
                () -> connectionCreationCount.getAndIncrement() == 0
                        ? initialConnector : replacementConnector,
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy());
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        Thread refreshThread = new Thread(() -> {
            try {
                session.refreshOnce();
            } catch (Throwable failure) {
                refreshFailure.set(failure);
            }
        });

        try {
            session.open();
            refreshThread.start();
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS), "refresh did not start");

            initialConnector.fail();
            assertNull(session.request(request()));
            awaitState(session, PhotonSession.State.OPEN);
            ResolvedConfig replacementConfig = session.getConfig();
            assertEquals("replacement", replacementConfig.getVersion());

            releaseReply.countDown();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(refreshThread.isAlive(), "refresh thread did not finish");
            assertNull(refreshFailure.get());
            assertSame(replacementConfig, session.getConfig());
            assertNull(session.request(request()));
            double nextDelay = session.refresher().nextRefreshDelaySeconds();
            assertTrue(nextDelay >= 24 && nextDelay <= 36,
                    "reconnect did not apply the replacement refresh interval: " + nextDelay);
        } finally {
            releaseReply.countDown();
            session.close();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
