package software.amazon.photon.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTcpProxy;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.resp.RespError;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.session.PhotonClient;
import software.amazon.photon.session.PhotonSession;
import software.amazon.photon.session.ReconnectPolicy;

class IamAuthClientIntegrationTest {
    private static final String FIRST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String ROTATED_ACCESS_KEY = "ASIAROTATEDCREDENTIAL";
    private static final AwsCredentialsProvider SDK_CREDENTIALS =
            StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("sdk-access-key", "sdk-secret-key"));

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        cache.setConfigJson("{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[]}");
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
    void syncAndAsyncClientsAuthenticateWithFreshToolkitCredentials() {
        AtomicInteger credentialCalls = new AtomicInteger();
        Supplier<AuthCredentials> authProvider =
                authProvider(
                        () -> {
                            int call = credentialCalls.incrementAndGet();
                            return AwsBasicCredentials.create(
                                    call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                                    call == 1 ? "first-secret" : "rotated-secret");
                        });

        try (PhotonClient.Result<DynamoDbClient> result =
                PhotonClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .sslContext(clientSslContext)
                        .authProvider(authProvider)
                        .origin(
                                DynamoDbClient.builder(),
                                builder -> builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                        .build()) {
            assertTrue(result.handle().isOpen());
        }

        try (PhotonClient.AsyncResult<DynamoDbAsyncClient> result =
                PhotonClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .sslContext(clientSslContext)
                        .authProvider(authProvider)
                        .buildAsync(
                                DynamoDbAsyncClient.builder(),
                                builder -> builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))) {
            assertTrue(result.handle().isOpen());
        }

        assertEquals(2, credentialCalls.get());
        List<MockCache.AuthAttempt> attempts = cache.getAuthAttempts();
        assertEquals(2, attempts.size());
        assertEquals("iam-user", attempts.get(0).getUserId());
        assertEquals("iam-user", attempts.get(1).getUserId());
        assertTrue(attempts.get(0).getToken().startsWith("my-cache/"));
        assertTrue(attempts.get(0).getToken().contains(FIRST_ACCESS_KEY));
        assertFalse(attempts.get(0).getToken().contains(ROTATED_ACCESS_KEY));
        assertTrue(attempts.get(1).getToken().contains(ROTATED_ACCESS_KEY));
        assertFalse(attempts.get(1).getToken().contains(FIRST_ACCESS_KEY));
        assertFalse(attempts.get(0).getToken().equals(attempts.get(1).getToken()));
        assertEquals(
                Arrays.asList("AUTH", "PHOTON.CONFIG", "AUTH", "PHOTON.CONFIG"),
                cache.getReceivedCommands());
    }

    @Test
    void nonOkSimpleAuthReplyDoesNotForceTokenRefresh() {
        AtomicInteger credentialCalls = new AtomicInteger();
        cache.setAuthReplyFunction(
                (userId, token) -> new RespSimpleString("NOT-OK".getBytes(StandardCharsets.UTF_8)));
        Supplier<AuthCredentials> authProvider =
                authProvider(
                        () -> {
                            credentialCalls.incrementAndGet();
                            return AwsBasicCredentials.create(FIRST_ACCESS_KEY, "first-secret");
                        });

        assertThrows(
                PhotonConnectionError.class,
                () ->
                        PhotonClient.builder()
                                .endpoint("localhost:" + server.getPort())
                                .sslContext(clientSslContext)
                                .authProvider(authProvider)
                                .origin(
                                        DynamoDbClient.builder(),
                                        builder ->
                                                builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                                .build());

        assertEquals(1, credentialCalls.get());
        assertEquals(3, cache.getAuthAttempts().size());
        assertTrue(
                cache.getAuthAttempts().stream()
                        .allMatch(attempt -> attempt.getToken().contains(FIRST_ACCESS_KEY)));
        assertAuthOnly(cache.getReceivedCommands());
    }

    @Test
    void reconnectUsesTokenInstalledByBackgroundRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch refreshInstalled = new CountDownLatch(1);
        IamAuthTokenProvider authProvider =
                IamAuthTokenProvider.builder()
                        .cacheName("My-Cache")
                        .userId("IAM-User")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                () -> {
                                    int call = credentialCalls.incrementAndGet();
                                    return AwsBasicCredentials.create(
                                            call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                                            call == 1 ? "first-secret" : "rotated-secret");
                                })
                        .refreshAfter(Duration.ofMillis(10))
                        .onTokenChanged(
                                token -> {
                                    if (token.contains(ROTATED_ACCESS_KEY)) {
                                        refreshInstalled.countDown();
                                    }
                                })
                        .build();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                PhotonClient.Result<DynamoDbClient> result =
                        PhotonClient.builder()
                                .endpoint("localhost:" + proxy.getPort())
                                .sslContext(clientSslContext)
                                .authProvider(authProvider)
                                .reconnectPolicy(
                                        ReconnectPolicy.builder().baseDelayMs(1).maxDelayMs(10).jitterFactor(0).build())
                                .origin(
                                        DynamoDbClient.builder(),
                                        builder ->
                                                builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                                .build()) {
            assertTrue(refreshInstalled.await(5, TimeUnit.SECONDS));

            proxy.disconnectClients();
            awaitReconnect(result.handle().getSession(), 2);

            List<MockCache.AuthAttempt> attempts = cache.getAuthAttempts();
            assertTrue(attempts.get(0).getToken().contains(FIRST_ACCESS_KEY));
            assertTrue(attempts.get(1).getToken().contains(ROTATED_ACCESS_KEY));
        }
    }

    @Test
    void authRejectionForcesRefreshBeforeBoundedRetry() {
        AtomicInteger credentialCalls = new AtomicInteger();
        cache.setAuthReplyFunction(
                (userId, token) ->
                        token.contains(FIRST_ACCESS_KEY)
                                ? new RespError("invalid-iam-credentials", "credentials rejected")
                                : new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)));
        Supplier<AuthCredentials> authProvider =
                authProvider(
                        () -> {
                            int call = credentialCalls.incrementAndGet();
                            return AwsBasicCredentials.create(
                                    call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                                    call == 1 ? "first-secret" : "rotated-secret");
                        });

        try (PhotonClient.Result<DynamoDbClient> result =
                PhotonClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .sslContext(clientSslContext)
                        .authProvider(authProvider)
                        .origin(
                                DynamoDbClient.builder(),
                                builder -> builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                        .build()) {
            assertTrue(result.handle().isOpen());
        }

        assertEquals(2, credentialCalls.get());
        List<MockCache.AuthAttempt> attempts = cache.getAuthAttempts();
        assertEquals(2, attempts.size());
        assertTrue(attempts.get(0).getToken().contains(FIRST_ACCESS_KEY));
        assertTrue(attempts.get(1).getToken().contains(ROTATED_ACCESS_KEY));
        assertEquals(Arrays.asList("AUTH", "AUTH", "PHOTON.CONFIG"), cache.getReceivedCommands());
    }

    @Test
    void clientCloseStopsInternallyManagedRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch refreshInstalled = new CountDownLatch(1);
        IamAuthTokenProvider authProvider =
                IamAuthTokenProvider.builder()
                        .cacheName("My-Cache")
                        .userId("IAM-User")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                () -> {
                                    int call = credentialCalls.incrementAndGet();
                                    return AwsBasicCredentials.create(
                                            call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                                            call == 1 ? "first-secret" : "rotated-secret");
                                })
                        .refreshAfter(Duration.ofMillis(10))
                        .onTokenChanged(
                                token -> {
                                    if (token.contains(ROTATED_ACCESS_KEY)) {
                                        refreshInstalled.countDown();
                                    }
                                })
                        .build();

        PhotonClient.Result<DynamoDbClient> result =
                PhotonClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .sslContext(clientSslContext)
                        .authProvider(authProvider)
                        .origin(
                                DynamoDbClient.builder(),
                                builder -> builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                        .build();
        try {
            assertTrue(refreshInstalled.await(5, TimeUnit.SECONDS));
        } finally {
            result.close();
        }
        Thread.sleep(50);
        int callsAfterClose = credentialCalls.get();
        Thread.sleep(100);

        assertEquals(callsAfterClose, credentialCalls.get());
    }

    @Test
    void asyncClientCloseStopsInternallyManagedRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();
        CountDownLatch refreshInstalled = new CountDownLatch(1);
        IamAuthTokenProvider authProvider =
                IamAuthTokenProvider.builder()
                        .cacheName("My-Cache")
                        .userId("IAM-User")
                        .region(Region.US_EAST_1)
                        .credentialsProvider(
                                () -> {
                                    int call = credentialCalls.incrementAndGet();
                                    return AwsBasicCredentials.create(
                                            call == 1 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                                            call == 1 ? "first-secret" : "rotated-secret");
                                })
                        .refreshAfter(Duration.ofMillis(10))
                        .onTokenChanged(
                                token -> {
                                    if (token.contains(ROTATED_ACCESS_KEY)) {
                                        refreshInstalled.countDown();
                                    }
                                })
                        .build();

        PhotonClient.AsyncResult<DynamoDbAsyncClient> result =
                PhotonClient.builder()
                        .endpoint("localhost:" + server.getPort())
                        .sslContext(clientSslContext)
                        .authProvider(authProvider)
                        .buildAsync(
                                DynamoDbAsyncClient.builder(),
                                builder -> builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS));
        try {
            assertTrue(refreshInstalled.await(5, TimeUnit.SECONDS));
        } finally {
            result.close();
        }
        Thread.sleep(50);
        int callsAfterClose = credentialCalls.get();
        Thread.sleep(100);

        assertEquals(callsAfterClose, credentialCalls.get());
    }

    @Test
    void invalidToolkitCredentialsFailBeforeConfiguration() {
        cache.setAuthReplyFunction(
                (userId, token) ->
                        token.contains("AKIAINVALIDCREDENTIAL")
                                ? new RespError("invalid-iam-credentials", "credentials rejected")
                                : new RespError("ERR", "unexpected token"));

        PhotonError error =
                assertThrows(
                        PhotonError.class,
                        () ->
                                PhotonClient.builder()
                                        .endpoint("localhost:" + server.getPort())
                                        .sslContext(clientSslContext)
                                        .authProvider(
                                                authProvider(
                                                        () ->
                                                                AwsBasicCredentials.create(
                                                                        "AKIAINVALIDCREDENTIAL", "invalid-secret")))
                                        .origin(
                                                DynamoDbClient.builder(),
                                                builder ->
                                                        builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                                        .build());

        assertTrue(error.getMessage().contains("AUTH failed"));
        assertFalse(cache.getAuthAttempts().isEmpty());
        assertTrue(
                cache.getAuthAttempts().stream()
                        .allMatch(attempt -> attempt.getToken().contains("AKIAINVALIDCREDENTIAL")));
        assertAuthOnly(cache.getReceivedCommands());
    }

    @Test
    void expiredTokenFailsBeforeConfiguration() {
        cache.setAuthReplyFunction(
                (userId, token) -> new RespError("expired-iam-token", "token expired"));

        PhotonError error =
                assertThrows(
                        PhotonError.class,
                        () ->
                                PhotonClient.builder()
                                        .endpoint("localhost:" + server.getPort())
                                        .sslContext(clientSslContext)
                                        .authCredentials("iam-user", "expired-token")
                                        .origin(
                                                DynamoDbClient.builder(),
                                                builder ->
                                                        builder.region(Region.US_EAST_1).credentialsProvider(SDK_CREDENTIALS))
                                        .build());

        assertTrue(error.getMessage().contains("AUTH failed"));
        assertEquals("expired-token", cache.getAuthAttempts().get(0).getToken());
        assertTrue(
                cache.getAuthAttempts().stream()
                        .allMatch(attempt -> "expired-token".equals(attempt.getToken())));
        assertAuthOnly(cache.getReceivedCommands());
    }

    private static IamAuthTokenProvider authProvider(AwsCredentialsProvider credentialsProvider) {
        return IamAuthTokenProvider.builder()
                .cacheName("My-Cache")
                .userId("IAM-User")
                .region(Region.US_EAST_1)
                .credentialsProvider(credentialsProvider)
                .build();
    }

    private static void assertAuthOnly(List<String> commands) {
        assertFalse(commands.isEmpty());
        assertTrue(commands.stream().allMatch("AUTH"::equals));
    }

    private void awaitReconnect(PhotonSession session, int expectedAuthAttempts)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((cache.getAuthAttempts().size() < expectedAuthAttempts
                        || session.getState() != PhotonSession.State.OPEN)
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(cache.getAuthAttempts().size() >= expectedAuthAttempts);
        assertEquals(PhotonSession.State.OPEN, session.getState());
    }
}
