package software.amazon.elasticache.integrations.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClientConfiguration;

/**
 * The IAM token is signed with the region and credentials of the application's own SDK client, so
 * these tests rotate or reject the credentials on the SDK client's provider.
 */
class IamAuthClientIntegrationTest {
    private static final String FIRST_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String ROTATED_ACCESS_KEY = "ASIAROTATEDCREDENTIAL";
    private static final long FAST_REFRESH_SECONDS = 1;

    @TempDir Path tempDir;

    private MockCache cache;
    private MockTlsServer server;
    private Path caBundle;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        cache.setConfigJson("{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[]}");
        server = new MockTlsServer(cache);
        caBundle = tempDir.resolve("ca.pem");
        server.writeCaBundle(caBundle);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void syncAndAsyncClientsAuthenticateWithFreshCredentials() {
        AtomicInteger credentialCalls = new AtomicInteger();
        AwsCredentialsProvider sdkCredentials = rotatingCredentials(credentialCalls);

        try (DynamoDbClient client =
                cacheBuilder()
                        .install(
                                DynamoDbClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(sdkCredentials))) {
            assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
        }

        try (DynamoDbAsyncClient client =
                cacheBuilder()
                        .installAsync(
                                DynamoDbAsyncClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(sdkCredentials))) {
            assertEquals(Region.US_EAST_1, client.serviceClientConfiguration().region());
        }

        assertEquals(4, credentialCalls.get());
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
                Arrays.asList("HELLO", "AWS.CONFIG", "HELLO", "AWS.CONFIG"), cache.getReceivedCommands());
    }

    @Test
    void nonMapHelloReplyDoesNotForceTokenRefresh() {
        AtomicInteger credentialCalls = new AtomicInteger();
        cache.setHelloReply(new RespSimpleString("NOT-OK".getBytes(StandardCharsets.UTF_8)));
        AwsCredentialsProvider sdkCredentials =
                () -> {
                    credentialCalls.incrementAndGet();
                    return AwsBasicCredentials.create(FIRST_ACCESS_KEY, "first-secret");
                };

        assertThrows(
                ElastiCacheIntegrationsConnectionError.class,
                () ->
                        cacheBuilder()
                                .clientConfiguration(
                                        ElastiCacheIntegrationsClientConfiguration.builder()
                                                .connectionTimeoutMs(1_000)
                                                .build())
                                .install(
                                        DynamoDbClient.builder()
                                                .region(Region.US_EAST_1)
                                                .credentialsProvider(sdkCredentials)));

        assertEquals(2, credentialCalls.get());
        assertTrue(cache.getAuthAttempts().size() >= 2, "startup retries until the timeout");
        assertTrue(
                cache.getAuthAttempts().stream()
                        .allMatch(attempt -> attempt.getToken().contains(FIRST_ACCESS_KEY)));
        assertHelloOnly(cache.getReceivedCommands());
    }

    @Test
    void reconnectUsesTokenInstalledByBackgroundRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                DynamoDbClient client =
                        cacheBuilder()
                                .endpoint("localhost:" + proxy.getPort())
                                .clientConfiguration(fastRefresh())
                                .install(
                                        DynamoDbClient.builder()
                                                .region(Region.US_EAST_1)
                                                .credentialsProvider(rotatingCredentials(credentialCalls)))) {
            // The toolkit schedules the next refresh only after it installs the current token, so
            // credential call 3 means the token from call 2 is installed.
            awaitAtLeast(credentialCalls, 3);

            proxy.disconnectClients();
            awaitAuthAttempts(2);

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

        try (DynamoDbClient client =
                cacheBuilder()
                        .install(
                                DynamoDbClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(rotatingCredentials(credentialCalls)))) {
            assertEquals(3, credentialCalls.get());
        }

        List<MockCache.AuthAttempt> attempts = cache.getAuthAttempts();
        assertEquals(2, attempts.size());
        assertTrue(attempts.get(0).getToken().contains(FIRST_ACCESS_KEY));
        assertTrue(attempts.get(1).getToken().contains(ROTATED_ACCESS_KEY));
        assertEquals(Arrays.asList("HELLO", "HELLO", "AWS.CONFIG"), cache.getReceivedCommands());
    }

    @Test
    void clientCloseStopsInternallyManagedRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();

        DynamoDbClient client =
                cacheBuilder()
                        .clientConfiguration(fastRefresh())
                        .install(
                                DynamoDbClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(rotatingCredentials(credentialCalls)));
        try {
            awaitAtLeast(credentialCalls, 3);
        } finally {
            client.close();
        }

        assertRefreshStopped(credentialCalls);
    }

    @Test
    void asyncClientCloseStopsInternallyManagedRefresh() throws Exception {
        AtomicInteger credentialCalls = new AtomicInteger();

        DynamoDbAsyncClient client =
                cacheBuilder()
                        .clientConfiguration(fastRefresh())
                        .installAsync(
                                DynamoDbAsyncClient.builder()
                                        .region(Region.US_EAST_1)
                                        .credentialsProvider(rotatingCredentials(credentialCalls)));
        try {
            awaitAtLeast(credentialCalls, 3);
        } finally {
            client.close();
        }

        assertRefreshStopped(credentialCalls);
    }

    @Test
    void invalidToolkitCredentialsFailBeforeConfiguration() {
        cache.setAuthReplyFunction(
                (userId, token) ->
                        token.contains("AKIAINVALIDCREDENTIAL")
                                ? new RespError("invalid-iam-credentials", "credentials rejected")
                                : new RespError("ERR", "unexpected token"));

        assertThrows(
                ElastiCacheIntegrationsError.class,
                () ->
                        cacheBuilder()
                                .install(
                                        DynamoDbClient.builder()
                                                .region(Region.US_EAST_1)
                                                .credentialsProvider(
                                                        () ->
                                                                AwsBasicCredentials.create(
                                                                        "AKIAINVALIDCREDENTIAL", "invalid-secret"))));

        assertFalse(cache.getAuthAttempts().isEmpty());
        assertTrue(
                cache.getAuthAttempts().stream()
                        .allMatch(attempt -> attempt.getToken().contains("AKIAINVALIDCREDENTIAL")));
        assertHelloOnly(cache.getReceivedCommands());
    }

    @Test
    void expiredTokenFailsBeforeConfiguration() {
        cache.setAuthReplyFunction(
                (userId, token) -> new RespError("expired-iam-token", "token expired"));

        assertThrows(
                ElastiCacheIntegrationsError.class,
                () ->
                        cacheBuilder()
                                .install(
                                        DynamoDbClient.builder()
                                                .region(Region.US_EAST_1)
                                                .credentialsProvider(
                                                        () -> AwsBasicCredentials.create(FIRST_ACCESS_KEY, "secret"))));

        assertTrue(cache.getAuthAttempts().get(0).getToken().startsWith("my-cache/"));
        assertHelloOnly(cache.getReceivedCommands());
    }

    private ElastiCacheIntegrationsClient.Builder cacheBuilder() {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("localhost:" + server.getPort())
                .cacheName("My-Cache")
                .userId("IAM-User")
                .caCerts(caBundle)
                // The default startup deadline can expire on a loaded CI runner.
                .clientConfiguration(
                        ElastiCacheIntegrationsClientConfiguration.builder()
                                .connectionTimeoutMs(10_000)
                                .build());
    }

    private static ElastiCacheIntegrationsClientConfiguration fastRefresh() {
        return ElastiCacheIntegrationsClientConfiguration.builder()
                .iamTokenRefreshDelaySeconds(FAST_REFRESH_SECONDS)
                .build();
    }

    /**
     * The first two calls, the install check and the first token, return the first key. Every later
     * call returns the rotated one.
     */
    private static AwsCredentialsProvider rotatingCredentials(AtomicInteger calls) {
        return () -> {
            int call = calls.incrementAndGet();
            return AwsBasicCredentials.create(
                    call <= 2 ? FIRST_ACCESS_KEY : ROTATED_ACCESS_KEY,
                    call <= 2 ? "first-secret" : "rotated-secret");
        };
    }

    private static void assertHelloOnly(List<String> commands) {
        assertFalse(commands.isEmpty());
        assertTrue(commands.stream().allMatch("HELLO"::equals));
    }

    private static void awaitAtLeast(AtomicInteger counter, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (counter.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(counter.get() >= expected, "expected at least " + expected + ", got " + counter);
    }

    private void awaitAuthAttempts(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.getAuthAttempts().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(cache.getAuthAttempts().size() >= expected);
    }

    private static void assertRefreshStopped(AtomicInteger credentialCalls)
            throws InterruptedException {
        Thread.sleep(50);
        int callsAfterClose = credentialCalls.get();
        // Longer than one refresh period, so a refresh that kept running would be seen.
        Thread.sleep(TimeUnit.SECONDS.toMillis(2 * FAST_REFRESH_SECONDS));

        assertEquals(callsAfterClose, credentialCalls.get());
    }
}
