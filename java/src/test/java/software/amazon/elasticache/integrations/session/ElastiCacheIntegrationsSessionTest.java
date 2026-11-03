package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsRequestCancellation;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsAuthError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigUnevaluable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespValue;

/** Unit tests for ElastiCacheIntegrationsSession. */
class ElastiCacheIntegrationsSessionTest {

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

    private ElastiCacheIntegrationsConnector createConnection() {
        return new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext);
    }

    // === Lifecycle tests ===

    @Test
    void open_transitionsToOpenState() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertEquals(ElastiCacheIntegrationsSession.State.CREATED, session.getState());

        session.open();
        assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
        assertTrue(session.isOpen());

        session.close();
    }

    @Test
    void close_transitionsToClosedState() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        assertTrue(session.isOpen());

        session.close();
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        assertFalse(session.isOpen());
    }

    @Test
    void close_isIdempotent() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        session.close();
        session.close(); // Should not throw
        session.close();

        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
    }

    @Test
    void open_onOpenedSession_throws() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        ElastiCacheIntegrationsError error =
                assertThrows(ElastiCacheIntegrationsError.class, session::open);
        assertTrue(error.getMessage().contains("open"));

        session.close();
    }

    @Test
    void open_onClosedSession_throws() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        ElastiCacheIntegrationsError error =
                assertThrows(ElastiCacheIntegrationsError.class, session::open);
        assertTrue(error.getMessage().contains("closed"));
    }

    @Test
    void reauthenticateOnce_usesFreshCredentialsOnTheLiveConnector() {
        TestConnector connector = new TestConnector();
        AtomicInteger credentialCalls = new AtomicInteger();
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () -> connector,
                        () -> new AuthCredentials(TEST_USER, "token-" + credentialCalls.incrementAndGet()),
                        null,
                        fastPolicy());
        try {
            session.open();

            session.reauthenticateOnce();

            assertEquals(2, credentialCalls.get());
            assertEquals(1, connector.getReauthenticationCount());
            assertEquals("token-2", connector.getLastReauthenticationToken());
        } finally {
            session.close();
        }
    }

    @Test
    void reauthenticateOnce_propagatesRejectionForRunnerBackoff() {
        TestConnector connector =
                new TestConnector() {
                    @Override
                    public void reauthenticate(String userId, String authToken) {
                        throw new ElastiCacheIntegrationsAuthError("IAM AUTH rejected: WRONGPASS");
                    }
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () -> connector, () -> new AuthCredentials(TEST_USER, TEST_TOKEN), null, fastPolicy());
        try {
            session.open();
            assertThrows(ElastiCacheIntegrationsAuthError.class, session::reauthenticateOnce);
        } finally {
            session.close();
        }
    }

    @Test
    void reauthenticateOnce_rejectionForceRefreshesManagedToken() {
        AtomicInteger credentialCalls = new AtomicInteger();
        ManagedIamAuthProvider authProvider = managedAuthProvider(credentialCalls);
        TestConnector connector =
                new TestConnector() {
                    @Override
                    public void reauthenticate(String userId, String authToken) {
                        throw new ElastiCacheIntegrationsAuthError("IAM AUTH rejected: WRONGPASS");
                    }
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(() -> connector, authProvider, null, fastPolicy());
        try {
            session.open();

            assertThrows(ElastiCacheIntegrationsAuthError.class, session::reauthenticateOnce);

            assertEquals(
                    2,
                    credentialCalls.get(),
                    "a rejected cached token must be replaced before the refresher retries");
        } finally {
            session.close();
            authProvider.close();
        }
    }

    @Test
    void reauthenticateOnce_deadConnectorDoesNotForceRefreshManagedToken() {
        AtomicInteger credentialCalls = new AtomicInteger();
        ManagedIamAuthProvider authProvider = managedAuthProvider(credentialCalls);
        TestConnector connector =
                new TestConnector() {
                    @Override
                    public void reauthenticate(String userId, String authToken) {
                        fail();
                        throw new ElastiCacheIntegrationsAuthError("Unexpected AUTH reply");
                    }
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(() -> connector, authProvider, null, fastPolicy());
        try {
            session.open();

            assertThrows(ElastiCacheIntegrationsAuthError.class, session::reauthenticateOnce);

            assertEquals(
                    1,
                    credentialCalls.get(),
                    "a dead connection must not invalidate otherwise reusable credentials");
        } finally {
            session.close();
            authProvider.close();
        }
    }

    @Test
    void reconnectPolicy_rejectsNonPositiveAuthRefreshIntervalMs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReconnectPolicy.builder().authRefreshIntervalMs(0).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ReconnectPolicy.builder().authRefreshIntervalMs(-1).build());
    }

    private static ManagedIamAuthProvider managedAuthProvider(AtomicInteger credentialCalls) {
        return new ManagedIamAuthProvider(
                IamAuthTokenProvider.builder()
                        .cacheName("test-cache")
                        .userId("test-user")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                () ->
                                        AwsBasicCredentials.create(
                                                "access-key-" + credentialCalls.incrementAndGet(), "secret-key"))
                        .refreshAfter(Duration.ofMinutes(10))
                        .build());
    }

    // === Refresher lifecycle tests ===

    /**
     * Captures the refreshers a session builds so a test can inspect their threads. The session's
     * package-private createRefresher() seam avoids adding any public constructor or getter.
     */
    private static class RefresherCapturingSession extends ElastiCacheIntegrationsSession {
        private final List<String> teardownOrder;
        private Refresher configCaptured;
        private Refresher reauthCaptured;

        RefresherCapturingSession(
                ElastiCacheIntegrationsConnector connector, List<String> teardownOrder) {
            super(connector, TEST_USER, TEST_TOKEN);
            this.teardownOrder = teardownOrder;
        }

        @Override
        Refresher createRefresher(
                double intervalSeconds,
                Runnable refreshOnce,
                String name,
                Refresher.FailureDelay failureDelay) {
            Refresher captured =
                    new Refresher(intervalSeconds, refreshOnce, name, 0, failureDelay) {
                        @Override
                        void stop(long timeoutMillis) {
                            if (teardownOrder != null) {
                                teardownOrder.add(name + ".stop");
                            }
                            super.stop(timeoutMillis);
                        }
                    };
            if ("aws-elasticache-integration-client-refresher".equals(name)) {
                configCaptured = captured;
            } else if ("aws-elasticache-integration-client-reauth".equals(name)) {
                reauthCaptured = captured;
            }
            return captured;
        }
    }

    @Test
    void open_startsRefresherThread() {
        RefresherCapturingSession session = new RefresherCapturingSession(createConnection(), null);
        assertNull(session.configCaptured);
        assertNull(session.reauthCaptured);

        session.open();
        try {
            assertNotNull(session.configCaptured);
            assertNotNull(session.reauthCaptured);
            assertTrue(session.configCaptured.thread().isAlive());
            assertTrue(session.reauthCaptured.thread().isAlive());
            assertEquals(
                    "aws-elasticache-integration-client-refresher",
                    session.configCaptured.thread().getName());
            assertEquals(
                    "aws-elasticache-integration-client-reauth", session.reauthCaptured.thread().getName());
            assertTrue(session.configCaptured.thread().isDaemon());
            assertTrue(session.reauthCaptured.thread().isDaemon());
        } finally {
            session.close();
        }
    }

    @Test
    void close_stopsRefresherThread() {
        RefresherCapturingSession session = new RefresherCapturingSession(createConnection(), null);
        session.open();
        Thread configThread = session.configCaptured.thread();
        Thread reauthThread = session.reauthCaptured.thread();
        assertTrue(configThread.isAlive());
        assertTrue(reauthThread.isAlive());

        session.close();

        assertFalse(configThread.isAlive());
        assertFalse(reauthThread.isAlive());
    }

    @Test
    void close_stopsRefresherBeforeClosingConnector() {
        // A refresh must never fire into a half-closed connection during an orderly shutdown.
        List<String> order = new ArrayList<>();
        ElastiCacheIntegrationsConnector connector =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext) {
                    @Override
                    public void close() {
                        order.add("connector.close");
                        super.close();
                    }
                };

        RefresherCapturingSession session = new RefresherCapturingSession(connector, order);
        session.open();
        session.close();

        assertEquals(
                Arrays.asList(
                        "aws-elasticache-integration-client-reauth.stop",
                        "aws-elasticache-integration-client-refresher.stop",
                        "connector.close"),
                order);
    }

    @Test
    void refresherConstructionFailure_isTerminalAndCleansUpConnector() {
        List<ElastiCacheIntegrationsConnector> created = new ArrayList<>();
        Supplier<ElastiCacheIntegrationsConnector> connectorFactory =
                () -> {
                    ElastiCacheIntegrationsConnector connector = createConnection();
                    created.add(connector);
                    return connector;
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        connectorFactory,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy()) {
                    @Override
                    Refresher createRefresher(
                            double intervalSeconds,
                            Runnable refreshOnce,
                            String name,
                            Refresher.FailureDelay failureDelay) {
                        throw new IllegalStateException("refresher construction failed");
                    }
                };

        assertThrows(IllegalStateException.class, session::open);
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        assertEquals(
                1, created.size(), "refresher construction failure must not retry the connect handshake");
        assertFalse(created.get(0).isReady(), "connector remained open after refresher failure");
        assertFalse(refresherThreadExists(), "a cache-refresher thread leaked from a failed open()");
    }

    @Test
    void refresherStartFailure_isTerminalAndCleansUpConnector() {
        List<ElastiCacheIntegrationsConnector> created = new ArrayList<>();
        Supplier<ElastiCacheIntegrationsConnector> connectorFactory =
                () -> {
                    ElastiCacheIntegrationsConnector connector = createConnection();
                    created.add(connector);
                    return connector;
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        connectorFactory,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy()) {
                    @Override
                    Refresher createRefresher(
                            double intervalSeconds,
                            Runnable refreshOnce,
                            String name,
                            Refresher.FailureDelay failureDelay) {
                        return new Refresher(intervalSeconds, refreshOnce, name, 0, failureDelay) {
                            @Override
                            void start() {
                                throw new RuntimeException("refresher start failed");
                            }
                        };
                    }
                };

        assertThrows(RuntimeException.class, session::open);
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        assertEquals(1, created.size(), "refresher start failure must not retry the connect handshake");
        assertFalse(created.get(0).isReady(), "connector remained open after refresher failure");
        assertFalse(refresherThreadExists(), "a cache-refresher thread leaked from a failed open()");
    }

    @Test
    void reauthRefresherStartFailure_stopsConfigRefresherAndCleansUpConnector() {
        List<ElastiCacheIntegrationsConnector> created = new ArrayList<>();
        Supplier<ElastiCacheIntegrationsConnector> connectorFactory =
                () -> {
                    ElastiCacheIntegrationsConnector connector = createConnection();
                    created.add(connector);
                    return connector;
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        connectorFactory,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy()) {
                    @Override
                    Refresher createRefresher(
                            double intervalSeconds,
                            Runnable refreshOnce,
                            String name,
                            Refresher.FailureDelay failureDelay) {
                        Refresher refresher =
                                new Refresher(intervalSeconds, refreshOnce, name, 0, failureDelay);
                        if ("aws-elasticache-integration-client-reauth".equals(name)) {
                            return new Refresher(intervalSeconds, refreshOnce, name, 0, failureDelay) {
                                @Override
                                void start() {
                                    throw new RuntimeException("reauth refresher start failed");
                                }
                            };
                        }
                        return refresher;
                    }
                };

        assertThrows(RuntimeException.class, session::open);
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        assertEquals(1, created.size());
        assertFalse(created.get(0).isReady());
        assertFalse(refresherThreadExists(), "a background refresher leaked from a failed open()");
    }

    @Test
    void close_duringConcurrentOpen_doesNotLeakRefresherThread() throws Exception {
        // Credential resolution is application code and may block indefinitely. close() must still
        // win the lifecycle, close the connector, and prevent open() from starting a refresher.
        CountDownLatch authProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseAuthProvider = new CountDownLatch(1);
        ElastiCacheIntegrationsSession blockingSession =
                new ElastiCacheIntegrationsSession(
                        createConnection(),
                        () -> {
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
            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, blockingSession.getState());
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

        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, blockingSession.getState());
        assertFalse(
                refresherThreadExists(), "a cache-refresher thread leaked from a close() racing open()");
    }

    /** True if either session background thread is still alive. */
    private static boolean refresherThreadExists() {
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int found = Thread.enumerate(threads);
        for (int i = 0; i < found; i++) {
            if (threads[i] != null
                    && ("aws-elasticache-integration-client-refresher".equals(threads[i].getName())
                            || "aws-elasticache-integration-client-reauth".equals(threads[i].getName()))
                    && threads[i].isAlive()) {
                return true;
            }
        }
        return false;
    }

    // === Request tests ===

    @Test
    void request_onOpenSession_succeeds() {
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/json"),
                        "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));

        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            ElastiCacheIntegrationsRequest request =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            Collections.singletonMap("content-type", "application/json"),
                            "{\"TableName\":\"Test\"}".getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem"));

            ElastiCacheIntegrationsResponse response = session.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
        } finally {
            session.close();
        }
    }

    @Test
    void authErrorPassesThroughAndStartsReconnect() {
        cache.setReply(Reply.error("AUTH_ERROR", "missing FAS"));
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            ElastiCacheIntegrationsResponse response =
                    session.request(
                            new ElastiCacheIntegrationsRequest(
                                    "POST",
                                    "https://dynamodb.us-east-1.amazonaws.com/",
                                    Collections.emptyMap(),
                                    new byte[0],
                                    new RequestMetadata("dynamodb", "UpdateItem")));

            assertNull(response);
            assertEquals(ElastiCacheIntegrationsSession.State.RECONNECTING, session.getState());
            assertFalse(session.isConnectionHealthy());
        } finally {
            session.close();
        }
    }

    @Test
    void unmatchedRequest_isDeclinedWithoutCacheRoundTrip() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            ElastiCacheIntegrationsResponse response =
                    session.request(
                            new ElastiCacheIntegrationsRequest(
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
        cache.setConfigJson(
                "{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[{"
                        + "\"expr\":\"service == 'dynamodb' && operation in ops "
                        + "&& has(body.TableName) && body.TableName in allowed\","
                        + "\"vars\":{\"ops\":[\"GetItem\",\"PutItem\"],"
                        + "\"allowed\":[\"Users\"]}}]}");
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ElastiCacheIntegrationsRequest allowed =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            Collections.emptyMap(),
                            "{\"TableName\":\"Users\"}".getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "PutItem"));
            ElastiCacheIntegrationsRequest other =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);

        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "GET",
                        "https://example.com/",
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem"));

        ElastiCacheIntegrationsError error =
                assertThrows(ElastiCacheIntegrationsError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("created"));
    }

    @Test
    void request_onClosedSession_throws() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "GET",
                        "https://example.com/",
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem"));

        ElastiCacheIntegrationsError error =
                assertThrows(ElastiCacheIntegrationsError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("closed"));
    }

    // === Config tests ===

    @Test
    void getConfig_afterOpen_returnsConfig() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertNull(session.getConfig());

        session.open();
        assertNotNull(session.getConfig());
        assertEquals("1.0", session.getConfig().getVersion());

        session.close();
    }

    @Test
    void open_withConfigOverrides_sendsOverrides() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("item_ttl", 120);

        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN, overrides);
        session.open();

        // Verify the config command was sent
        assertTrue(cache.getReceivedCommands().contains("AWS.CONFIG"));

        session.close();
    }

    // === Auth provider tests ===

    @Test
    void open_callsAuthProvider() {
        AtomicInteger authProviderCallCount = new AtomicInteger(0);
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        createConnection(),
                        () -> {
                            authProviderCallCount.incrementAndGet();
                            return new AuthCredentials(TEST_USER, "dynamic-token");
                        });

        session.open();
        assertEquals(1, authProviderCallCount.get());

        session.close();
    }

    @Test
    void open_closesOnAuthFailure() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        createConnection(),
                        () -> {
                            throw new RuntimeException("Auth provider failed");
                        });

        assertThrows(RuntimeException.class, session::open);
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
    }

    @Test
    void reconnectsThroughProxyWithFreshCredentials() throws Exception {
        AtomicInteger credentialsCallCount = new AtomicInteger();
        cache.setReply(Reply.ok(200, Collections.emptyMap(), "reconnected"));

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort())) {
            ElastiCacheIntegrationsHandle handle =
                    ElastiCacheIntegrationsHandle.builder()
                            .host("localhost")
                            .port(proxy.getPort())
                            .sslContext(clientSslContext)
                            .authProvider(
                                    () ->
                                            new AuthCredentials(
                                                    TEST_USER, "token-" + credentialsCallCount.incrementAndGet()))
                            .reconnectPolicy(fastPolicy())
                            .build();
            try {
                handle.open();
                proxy.disconnectClients();

                try {
                    handle.getSession().request(request());
                } catch (ElastiCacheIntegrationsError expectedFirstRequestFailure) {
                    // A request already admitted to the failed socket is not replayed.
                }

                awaitState(handle.getSession(), ElastiCacheIntegrationsSession.State.OPEN);
                ElastiCacheIntegrationsResponse response = handle.getSession().request(request());
                assertEquals("reconnected", new String(response.getBody(), StandardCharsets.UTF_8));
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : replacementConnector,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy());
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
        try {
            session.open();

            CompletableFuture<?> failedRequest =
                    CompletableFuture.runAsync(() -> session.request(request()), requestExecutor);
            assertThrows(ExecutionException.class, () -> failedRequest.get(1, TimeUnit.SECONDS));
            assertTrue(recoveryStarted.await(1, TimeUnit.SECONDS));
            assertFalse(allowRecovery.await(ASYNC_OBSERVATION_MS, TimeUnit.MILLISECONDS));

            allowRecovery.countDown();
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
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
        ReconnectPolicy policy =
                ReconnectPolicy.builder()
                        .fallbackDurationSeconds(0)
                        .baseDelayMs(1)
                        .maxDelayMs(1)
                        .jitterFactor(0)
                        .build();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort())) {
            ElastiCacheIntegrationsHandle handle =
                    ElastiCacheIntegrationsHandle.builder()
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
                awaitState(handle.getSession(), ElastiCacheIntegrationsSession.State.RECONNECTING);
                assertThrows(
                        software.amazon.elasticache.integrations.exception
                                .ElastiCacheIntegrationsCacheUnavailable.class,
                        () -> handle.getSession().request(request()));

                proxy.enableForwarding();
                awaitState(handle.getSession(), ElastiCacheIntegrationsSession.State.OPEN);
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : new TestConnector(true),
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
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(connectionCreationCount.get() >= 3);
            assertEquals(ElastiCacheIntegrationsSession.State.RECONNECTING, session.getState());
            assertNull(session.request(request()));
        } finally {
            session.close();
        }
    }

    @Test
    void zeroRetryDelayReturnsWithoutWaiting() throws Exception {
        ElastiCacheIntegrationsSession session = reconnectingSession();
        try {
            CompletableFuture<Boolean> retry =
                    CompletableFuture.supplyAsync(() -> session.waitForRetry(0));

            assertTrue(retry.get(1, TimeUnit.SECONDS));
        } finally {
            session.close();
        }
    }

    @Test
    void earlyWakeupKeepsRequestedRetryDelay() throws Exception {
        long delayMs = 200;
        ElastiCacheIntegrationsSession session = reconnectingSession();
        try {
            CountDownLatch waiting = new CountDownLatch(1);
            CompletableFuture<Long> elapsedMs =
                    CompletableFuture.supplyAsync(
                            () -> {
                                long start = System.nanoTime();
                                waiting.countDown();
                                assertTrue(session.waitForRetry(delayMs));
                                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            Thread.sleep(ASYNC_OBSERVATION_MS);
            session.wakeStateWaiters();

            assertTrue(elapsedMs.get(2, TimeUnit.SECONDS) >= delayMs);
        } finally {
            session.close();
        }
    }

    /** Return a session stuck in RECONNECTING whose own reconnect loop sits in a long backoff. */
    private ElastiCacheIntegrationsSession reconnectingSession() throws Exception {
        AtomicInteger connectionCreationCount = new AtomicInteger();
        TestConnector initialConnector = new TestConnector();
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : new TestConnector(true),
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        ReconnectPolicy.builder()
                                .baseDelayMs(60_000)
                                .maxDelayMs(60_000)
                                .jitterFactor(0)
                                .build());
        session.open();
        initialConnector.fail();
        assertNull(session.request(request()));
        awaitState(session, ElastiCacheIntegrationsSession.State.RECONNECTING);
        return session;
    }

    @Test
    void reconnectReevaluatesRequestWithFreshPredicates() throws Exception {
        TestConnector initialConnector = new TestConnector(false, "true");
        TestConnector replacementConnector = new TestConnector(false, "false");
        AtomicInteger connectionCreationCount = new AtomicInteger();
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : replacementConnector,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy());
        try {
            session.open();
            initialConnector.fail();

            assertNull(session.request(request()));
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
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
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);

            assertEquals(200, session.request(request()).getStatusCode());
            assertEquals(3, connectionCreationCount.get());
            assertTrue(dyingReplacement.isDead());
        } finally {
            session.close();
        }
    }

    @Test
    void reconnectRetriesNonCacheConfigFailureWithoutPublishingPartialState() throws Exception {
        TestConnector initialConnector = new TestConnector(false, "true");
        FailingConfigConnector failingReplacement = new FailingConfigConnector();
        CountDownLatch stableStarted = new CountDownLatch(1);
        CountDownLatch allowStable = new CountDownLatch(1);
        BlockingConnectConnector stableReplacement =
                new BlockingConnectConnector(stableStarted, allowStable, "false");
        AtomicInteger connectionCreationCount = new AtomicInteger();
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
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
            assertEquals(ElastiCacheIntegrationsSession.State.RECONNECTING, session.getState());
            assertSame(initialConfig, session.getConfig());
            assertTrue(failingReplacement.isDead());

            allowStable.countDown();
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
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
        Supplier<ElastiCacheIntegrationsConnector> connectorFactory =
                () ->
                        connectionCreationCount.getAndIncrement() == 0
                                ? initialConnector
                                : replacementConnector;
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        connectorFactory,
                        () -> new AuthCredentials(TEST_USER, "token-" + credentialsCallCount.incrementAndGet()),
                        null,
                        fastPolicy());
        ExecutorService requestExecutor = Executors.newFixedThreadPool(8);
        try {
            session.open();
            initialConnector.fail();

            CompletableFuture<?>[] requests = new CompletableFuture<?>[8];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = CompletableFuture.runAsync(() -> session.request(request()), requestExecutor);
            }
            CompletableFuture.allOf(requests).get(5, TimeUnit.SECONDS);

            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : new TestConnector(true),
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        ReconnectPolicy.builder().baseDelayMs(1_000).maxDelayMs(1_000).jitterFactor(0).build());
        try {
            session.open();
            initialConnector.fail();
            assertNull(session.request(request()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (connectionCreationCount.get() < 2 && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertTrue(connectionCreationCount.get() >= 2);

            session.close();
            int attemptsAfterClose = connectionCreationCount.get();
            Thread.sleep(ASYNC_OBSERVATION_MS);
            assertEquals(attemptsAfterClose, connectionCreationCount.get());
            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        } finally {
            session.close();
        }
    }

    private static ReconnectPolicy fastPolicy() {
        return ReconnectPolicy.builder().baseDelayMs(1).maxDelayMs(2).jitterFactor(0).build();
    }

    private static ElastiCacheIntegrationsRequest request() {
        return new ElastiCacheIntegrationsRequest(
                "GET",
                "https://example.com/",
                Collections.emptyMap(),
                new byte[0],
                new RequestMetadata("dynamodb", "GetItem"));
    }

    private static void awaitState(
            ElastiCacheIntegrationsSession session, ElastiCacheIntegrationsSession.State expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, session.getState());
    }

    private static class TestConnector extends ElastiCacheIntegrationsConnector {
        private volatile boolean dead;
        private final boolean failConnect;
        private final String predicateExpression;
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicInteger reauthenticationCount = new AtomicInteger();
        private volatile String lastReauthenticationToken;

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

        void fail() {
            dead = true;
        }

        private int getRequestCount() {
            return requestCount.get();
        }

        private int getReauthenticationCount() {
            return reauthenticationCount.get();
        }

        private String getLastReauthenticationToken() {
            return lastReauthenticationToken;
        }

        @Override
        public void connect() {
            if (failConnect) {
                throw new ElastiCacheIntegrationsConnectionError("injected connection failure");
            }
            dead = false;
        }

        @Override
        public void handshake(String userId, String authToken) {
            // Test connections accept any credentials without a protocol exchange.
        }

        @Override
        public void reauthenticate(String userId, String authToken) {
            reauthenticationCount.incrementAndGet();
            lastReauthenticationToken = authToken;
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
        public ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            requestCount.incrementAndGet();
            return new ElastiCacheIntegrationsResponse(200, Collections.emptyMap());
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
        public ElastiCacheIntegrationsResponse request(
                ElastiCacheIntegrationsRequest request,
                ElastiCacheIntegrationsRequestCancellation cancellation) {
            fail();
            throw new ElastiCacheIntegrationsConnectionError("injected request failure");
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
            throw new IllegalStateException("injected non-Cache config failure");
        }
    }

    private static final class BlockingConnectConnector extends TestConnector {
        private final CountDownLatch started;
        private final CountDownLatch proceed;

        private BlockingConnectConnector(CountDownLatch started, CountDownLatch proceed) {
            this(started, proceed, "true");
        }

        private BlockingConnectConnector(
                CountDownLatch started, CountDownLatch proceed, String predicateExpression) {
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
                throw new ElastiCacheIntegrationsConnectionError("interrupted recovery", e);
            }
            super.connect();
        }
    }

    // === Refresh: fetch and apply ===

    @Test
    void refreshOnce_sendsNoArgumentFormAfterBringUp() {
        // Bring-up already sent the overrides; the first tick sends the no-argument form.
        cache.setNoArgConfigReply(null); // RESP3 null: "unchanged"
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        createConnection(), TEST_USER, TEST_TOKEN, Collections.singletonMap("flag", true));
        try {
            session.open();
            int commandsBeforeTick = cache.getReceivedCommands().size();

            session.refreshOnce();

            assertEquals(commandsBeforeTick + 1, cache.getReceivedCommands().size());
            assertFalse(
                    cache.wasOverridesSupplied(), "the tick after bring-up should send the no-argument form");
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_nullReplyLeavesConfigAndPredicatesUnchanged() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            ElastiCacheIntegrationsRequest matchingRequest =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            Collections.emptyMap(),
                            "{}".getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem"));
            ElastiCacheIntegrationsRequest nonMatchingRequest =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            Collections.emptyMap(),
                            "{}".getBytes(StandardCharsets.UTF_8),
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ElastiCacheIntegrationsRequest matchingRequest =
                    new ElastiCacheIntegrationsRequest(
                            "POST",
                            "https://dynamodb.us-east-1.amazonaws.com/",
                            Collections.emptyMap(),
                            "{}".getBytes(StandardCharsets.UTF_8),
                            new RequestMetadata("dynamodb", "GetItem"));
            assertNotNull(session.request(matchingRequest));
            int commandsBeforeRefresh = cache.getReceivedCommands().size();

            cache.setNoArgConfigReply("{\"version\":\"2.0\",\"refresh_interval\":45,\"predicates\":[]}");

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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        createConnection(), TEST_USER, TEST_TOKEN, Collections.singletonMap("flag", true));
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setFailureMode(MockCache.FailureMode.DROP);
            cache.setFailureCommand("aws.config");

            assertDoesNotThrow(session::refreshOnce);

            assertSame(originalConfig, session.getConfig());
        } finally {
            session.close();
        }
    }

    @Test
    void refreshOnce_swallowsMalformedReplyAndKeepsCurrentConfig() {
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(createConnection(), TEST_USER, TEST_TOKEN);
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
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        createConnection(), TEST_USER, TEST_TOKEN, Collections.singletonMap("flag", true));
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
        ElastiCacheIntegrationsConnector connector =
                new ElastiCacheIntegrationsConnector("localhost", server.getPort(), clientSslContext) {
                    @Override
                    public RespValue execute(String command, byte[]... args) {
                        if (ElastiCacheIntegrationsProtocol.CONFIG_COMMAND.equals(command)) {
                            refreshStarted.countDown();
                            try {
                                if (!releaseReply.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("timed out waiting to release config reply");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(e);
                            }
                            return new RespBulkString(
                                    "{\"version\":\"2.0\",\"refresh_interval\":45,\"predicates\":[]}"
                                            .getBytes(StandardCharsets.UTF_8));
                        }
                        return super.execute(command, args);
                    }
                };
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(connector, TEST_USER, TEST_TOKEN);
        session.open();
        ResolvedConfig originalConfig = session.getConfig();
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        Thread refreshThread =
                new Thread(
                        () -> {
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
        TestConnector initialConnector =
                new TestConnector(false, "true") {
                    @Override
                    public RespValue execute(String command, byte[]... args) {
                        refreshStarted.countDown();
                        try {
                            if (!releaseReply.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("timed out waiting to release stale config reply");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new ElastiCacheIntegrationsConnectionError("stale refresh interrupted", e);
                        }
                        return new RespBulkString(
                                ("{\"version\":\"stale\",\"refresh_interval\":90,"
                                                + "\"predicates\":[{\"expr\":\"true\"}]}")
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                };
        TestConnector replacementConnector =
                new TestConnector(false, "false") {
                    @Override
                    public ResolvedConfig configureAndStartReader(Map<String, Object> configOverrides) {
                        Map<String, Object> predicate =
                                Collections.<String, Object>singletonMap("expr", "false");
                        Map<String, Object> rawConfig =
                                Collections.<String, Object>singletonMap(
                                        "predicates", Collections.singletonList(predicate));
                        return new ResolvedConfig("replacement", 30, rawConfig);
                    }
                };
        AtomicInteger connectionCreationCount = new AtomicInteger();
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        () ->
                                connectionCreationCount.getAndIncrement() == 0
                                        ? initialConnector
                                        : replacementConnector,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy());
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        Thread refreshThread =
                new Thread(
                        () -> {
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
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
            ResolvedConfig replacementConfig = session.getConfig();
            assertEquals("replacement", replacementConfig.getVersion());

            releaseReply.countDown();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));

            assertFalse(refreshThread.isAlive(), "refresh thread did not finish");
            assertNull(refreshFailure.get());
            assertSame(replacementConfig, session.getConfig());
            assertNull(session.request(request()));
            double nextDelay = session.configRefresher().nextRefreshDelaySeconds();
            assertTrue(
                    nextDelay >= 24 && nextDelay <= 36,
                    "reconnect did not apply the replacement refresh interval: " + nextDelay);
        } finally {
            releaseReply.countDown();
            session.close();
            refreshThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    // === Config staleness tests ===

    private static final long STALENESS_DURATION_SECONDS = 10;

    /** Session over MockCache connections with a fake clock and a 10-second staleness window. */
    private ElastiCacheIntegrationsSession stalenessSession(
            AtomicLong clock, AtomicInteger connections) {
        return new ElastiCacheIntegrationsSession(
                () -> {
                    connections.incrementAndGet();
                    return createConnection();
                },
                () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                null,
                fastPolicy(),
                STALENESS_DURATION_SECONDS,
                clock::get);
    }

    private static void advanceSeconds(AtomicLong clock, long seconds) {
        clock.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private static void awaitConnections(AtomicInteger connections, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (connections.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, connections.get());
    }

    @Test
    void staleness_failureBeforeWindowKeepsConnector() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval

            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce();

            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertSame(originalConfig, session.getConfig());
            assertEquals(1, connections.get());
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_failureAfterWindowDropsConnector() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval

            advanceSeconds(clock, STALENESS_DURATION_SECONDS);
            session.refreshOnce();

            awaitConnections(connections, 2);
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_nullReplyResetsClock() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            int connectionsAfterOpen = connections.get();

            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce(); // RESP3 null: unchanged

            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval
            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce();

            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertEquals(connectionsAfterOpen, connections.get());
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_appliedConfigResetsClock() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();

            cache.setNoArgConfigReply("{\"version\":\"2.0\",\"refresh_interval\":60,\"predicates\":[]}");
            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce();
            assertEquals("2.0", session.getConfig().getVersion());

            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval
            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce();

            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertEquals(1, connections.get());
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_reconnectResetsClock() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            cache.setNoArgConfigReply("{\"version\":\"1.0\"}"); // missing refresh_interval
            advanceSeconds(clock, STALENESS_DURATION_SECONDS);
            session.refreshOnce();
            awaitConnections(connections, 2);
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);

            // The replacement published a fresh config, so one soft failure is not yet stale.
            session.refreshOnce();

            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertEquals(2, connections.get());
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_rejectsInvalidDuration() {
        for (long invalid : new long[] {0, -1, Long.MAX_VALUE}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ElastiCacheIntegrationsSession(
                                    this::createConnection,
                                    () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                                    null,
                                    fastPolicy(),
                                    invalid));
        }
    }

    @Test
    void handle_closingTheTransportClosesTheSession() {
        ElastiCacheIntegrationsHandle handle =
                ElastiCacheIntegrationsHandle.builder()
                        .host("localhost")
                        .port(server.getPort())
                        .sslContext(clientSslContext)
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .build();
        try {
            handle.open();

            // An SDK client built with httpClientBuilder(...) owns the transport and closes it when
            // the application closes the client. A transport passed to httpClient(...) is never
            // closed by the SDK, so this test closes it the way the SDK-owned path does.
            handle.getHttpClient().close();

            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.getSession().getState());
        } finally {
            handle.close();
        }
    }

    @Test
    void asyncHandle_closingTheTransportClosesTheSession() {
        ElastiCacheIntegrationsAsyncHandle handle =
                ElastiCacheIntegrationsAsyncHandle.builder()
                        .host("localhost")
                        .port(server.getPort())
                        .sslContext(clientSslContext)
                        .authCredentials(TEST_USER, TEST_TOKEN)
                        .build();
        try {
            handle.open();

            // An SDK client built with httpClientBuilder(...) owns the transport and closes it when
            // the application closes the client. A transport passed to httpClient(...) is never
            // closed by the SDK, so this test closes it the way the SDK-owned path does.
            handle.getHttpClient().close();

            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.getSession().getState());
        } finally {
            handle.close();
        }
    }

    @Test
    void staleness_handleBuilderPassesDurationToSession() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        ElastiCacheIntegrationsHandle.builder()
                                .host("localhost")
                                .port(server.getPort())
                                .sslContext(clientSslContext)
                                .authCredentials(TEST_USER, TEST_TOKEN)
                                .stalenessDurationSeconds(0)
                                .build());
    }

    @Test
    void staleness_isConfigStaleTracksConfirmation() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            assertFalse(session.isConfigStale());

            advanceSeconds(clock, STALENESS_DURATION_SECONDS);
            assertTrue(session.isConfigStale());

            session.refreshOnce(); // RESP3 null: unchanged
            assertFalse(session.isConfigStale());
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_overridesRejectedKeepsConfigUntilWindowPasses() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setNoArgConfigError(new RespError("CONFIG_INVALID", "unknown override key"));

            advanceSeconds(clock, STALENESS_DURATION_SECONDS - 1);
            session.refreshOnce();
            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertSame(originalConfig, session.getConfig());
            assertEquals(1, connections.get());

            advanceSeconds(clock, 1);
            session.refreshOnce();
            awaitConnections(connections, 2);
            awaitState(session, ElastiCacheIntegrationsSession.State.OPEN);
        } finally {
            session.close();
        }
    }

    @Test
    void staleness_unevaluablePredicateOnRefreshKeepsLastGoodConfig() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger connections = new AtomicInteger();
        ElastiCacheIntegrationsSession session = stalenessSession(clock, connections);
        try {
            session.open();
            ResolvedConfig originalConfig = session.getConfig();
            cache.setNoArgConfigReply(
                    "{\"version\":\"2.0\",\"refresh_interval\":60,"
                            + "\"predicates\":[{\"expr\":\"operation == 'GetItem'\"},"
                            + "{\"expr\":\"this is not (valid CEL\"}]}");

            session.refreshOnce();

            assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
            assertSame(originalConfig, session.getConfig());
            assertFalse(session.isConfigStale());
            assertEquals(1, connections.get());
        } finally {
            session.close();
        }
    }

    @Test
    void open_failsWithOverridesRejectedWhenTheCacheRejectsTheOverrides() {
        cache.setConfigError(new RespError("CONFIG_INVALID", "unknown override key"));
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        this::createConnection,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        Collections.singletonMap("item_ttl", 60),
                        fastPolicy());
        try {
            ElastiCacheIntegrationsOverridesRejected thrown =
                    assertThrows(ElastiCacheIntegrationsOverridesRejected.class, session::open);
            assertTrue(thrown.getMessage().contains("unknown override key"));
            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        } finally {
            session.close();
        }
    }

    @Test
    void open_failsOnConfigWithUnevaluablePredicate() {
        cache.setConfigJson(
                "{\"version\":\"1.0\",\"refresh_interval\":60,"
                        + "\"predicates\":[{\"expr\":\"this is not (valid CEL\"}]}");
        ElastiCacheIntegrationsSession session =
                new ElastiCacheIntegrationsSession(
                        this::createConnection,
                        () -> new AuthCredentials(TEST_USER, TEST_TOKEN),
                        null,
                        fastPolicy());
        try {
            assertThrows(ElastiCacheIntegrationsConfigUnevaluable.class, session::open);
            assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, session.getState());
        } finally {
            session.close();
        }
    }
}
