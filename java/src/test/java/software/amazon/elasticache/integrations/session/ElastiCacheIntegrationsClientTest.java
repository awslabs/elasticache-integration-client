package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkClientOption;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.identity.spi.ResolveIdentityRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.mock.MockCache;
import software.amazon.elasticache.integrations.mock.MockTcpProxy;
import software.amazon.elasticache.integrations.mock.MockTlsServer;
import software.amazon.elasticache.integrations.mock.ReceivedRequest;
import software.amazon.elasticache.integrations.mock.Reply;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespSimpleString;

class ElastiCacheIntegrationsClientTest {

    private static final String OWN_ACCESS_KEY = "AKIDOWNCREDENTIAL01";
    private static final String DEFAULT_CHAIN_ACCESS_KEY = "AKIDDEFAULTCHAIN01";

    @TempDir Path tempDir;

    private MockCache cache;
    private MockTlsServer server;
    private Path caBundle;
    private final AtomicReference<ElastiCacheIntegrationsHandle> handle = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        cache.setConfigJson(
                "{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[{\"expr\":\"true\"}]}");
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

    /** The application's own builder: its region and credentials, and an origin nobody can reach. */
    private static DynamoDbClientBuilder appBuilder() {
        return DynamoDbClient.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(OWN_ACCESS_KEY, "own-secret-key")))
                .endpointOverride(URI.create("http://127.0.0.1:1"));
    }

    private ElastiCacheIntegrationsClient.Builder cacheBuilder() {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint("localhost:" + server.getPort())
                .cacheName("my-cache")
                .caCerts(caBundle)
                // The default startup deadline can expire on a loaded CI runner.
                .clientConfiguration(
                        ElastiCacheIntegrationsClientConfiguration.builder()
                                .connectionTimeoutMs(10_000)
                                .build())
                .handleObserver(handle::set);
    }

    private void replyWithTables(String... names) {
        StringBuilder body = new StringBuilder("{\"TableNames\":[");
        for (int i = 0; i < names.length; i++) {
            body.append(i == 0 ? "" : ",").append('"').append(names[i]).append('"');
        }
        cache.setReply(
                Reply.ok(
                        200,
                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                        body.append("]}").toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void clientWiresUpTheSuppliedClient() {
        replyWithTables("from-cache");

        try (DynamoDbClient client = cacheBuilder().install(appBuilder())) {
            assertEquals(Collections.singletonList("from-cache"), client.listTables().tableNames());
            assertTrue(handle.get().isOpen());
        }

        ReceivedRequest request = cache.getLastRequest();
        assertNotNull(request);
        assertEquals("POST", request.getMethod());
        assertEquals("service=dynamodb&op=ListTables", request.getMetadata());
        assertFalse(request.getHeaders().contains("authorization"));
        assertFalse(request.getHeaders().contains("x-amz-date"));
    }

    @Test
    void clientCanBeInstalledAgainAfterClose() {
        DynamoDbClientBuilder builder = appBuilder();
        cacheBuilder().install(builder).close();
        assertTrue(
                builder.overrideConfiguration().executionInterceptors().isEmpty(),
                "install leaves the application's builder as it was");

        replyWithTables("from-cache");
        try (DynamoDbClient client = cacheBuilder().install(builder)) {
            assertEquals(Collections.singletonList("from-cache"), client.listTables().tableNames());
        }
    }

    @Test
    void clientClosesTheSessionWhenAuthenticationFails() {
        cache.setAuthReplyFunction((userId, token) -> new RespError("WRONGPASS", "rejected"));

        ElastiCacheIntegrationsError error =
                assertThrows(
                        ElastiCacheIntegrationsError.class, () -> cacheBuilder().install(appBuilder()));

        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
        assertThrows(
                ElastiCacheIntegrationsError.class,
                handle.get().getOwnedAuthProvider()::get,
                "the token manager is closed");
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void clientKeepsTheSuppliedBuilderUsableWhenBringupFails() {
        AtomicBoolean reject = new AtomicBoolean(true);
        cache.setAuthReplyFunction(
                (userId, token) ->
                        reject.get()
                                ? new RespError("WRONGPASS", "rejected")
                                : new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)));
        DynamoDbClientBuilder builder = appBuilder();
        assertThrows(ElastiCacheIntegrationsError.class, () -> cacheBuilder().install(builder));

        reject.set(false);
        replyWithTables("from-cache");
        try (DynamoDbClient client = cacheBuilder().install(builder)) {
            assertEquals(Collections.singletonList("from-cache"), client.listTables().tableNames());
        }
    }

    @Test
    void clientSignsTheCacheTokenWithTheClientsOwnCredentials() {
        // The default chain resolves to another identity, so a fallback to it signs with the wrong key.
        Map<String, String> defaultChain = new HashMap<>();
        defaultChain.put("aws.accessKeyId", DEFAULT_CHAIN_ACCESS_KEY);
        defaultChain.put("aws.secretAccessKey", "default-chain-secret-key");
        withSystemProperties(
                defaultChain,
                () -> {
                    try (DynamoDbClient client = cacheBuilder().userId("IAM-User").install(appBuilder())) {
                        assertTrue(handle.get().isOpen());
                    }
                });

        MockCache.AuthAttempt attempt = cache.getAuthAttempts().get(0);
        assertEquals("iam-user", attempt.getUserId());
        assertTrue(attempt.getToken().startsWith("my-cache/"));
        assertTrue(attempt.getToken().contains("X-Amz-Credential=" + OWN_ACCESS_KEY + "%2F"));
        assertFalse(attempt.getToken().contains(DEFAULT_CHAIN_ACCESS_KEY));
    }

    @Test
    void clientResolvesTheCredentialsAgainForEachNewToken() {
        AtomicInteger calls = new AtomicInteger();
        DynamoDbClientBuilder builder =
                appBuilder()
                        .credentialsProvider(
                                () ->
                                        AwsBasicCredentials.create(
                                                "AKIDROTATING" + calls.incrementAndGet(), "rotating-secret"));

        try (DynamoDbClient client = cacheBuilder().install(builder)) {
            int firstCall = calls.get();
            MockCache.AuthAttempt first = cache.getAuthAttempts().get(0);
            handle.get().getOwnedAuthProvider().refresh();

            assertTrue(first.getToken().contains("X-Amz-Credential=AKIDROTATING" + firstCall + "%2F"));
            assertTrue(
                    handle
                            .get()
                            .getOwnedAuthProvider()
                            .get()
                            .getToken()
                            .contains("X-Amz-Credential=AKIDROTATING" + (firstCall + 1) + "%2F"));
        }
    }

    @Test
    void clientSignsTheCacheTokenWithAnIdentityProviderThatIsNotACredentialsProvider() {
        IdentityProvider<AwsSessionCredentialsIdentity> sessionIdentity =
                new IdentityProvider<AwsSessionCredentialsIdentity>() {
                    @Override
                    public Class<AwsSessionCredentialsIdentity> identityType() {
                        return AwsSessionCredentialsIdentity.class;
                    }

                    @Override
                    public CompletableFuture<AwsSessionCredentialsIdentity> resolveIdentity(
                            ResolveIdentityRequest request) {
                        return CompletableFuture.completedFuture(
                                AwsSessionCredentialsIdentity.create(
                                        "ASIASESSIONCREDENTIAL", "session-secret", "session-token-value"));
                    }
                };

        try (DynamoDbClient client =
                cacheBuilder().install(appBuilder().credentialsProvider(sessionIdentity))) {
            assertTrue(handle.get().isOpen());
        }

        String token = cache.getAuthAttempts().get(0).getToken();
        assertTrue(token.contains("X-Amz-Credential=ASIASESSIONCREDENTIAL%2F"));
        assertTrue(token.contains("X-Amz-Security-Token="));
    }

    @Test
    void clientSignsTheCacheTokenForTheClientsRegion() {
        try (DynamoDbClient client =
                cacheBuilder().install(appBuilder().region(Region.AP_SOUTHEAST_2))) {
            assertTrue(handle.get().isOpen());
        }

        assertTrue(
                cache.getAuthAttempts().get(0).getToken().contains("%2Fap-southeast-2%2Felasticache%2F"));
    }

    @Test
    void clientRejectsAnUnsignedClient() {
        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () ->
                                cacheBuilder()
                                        .install(
                                                appBuilder().credentialsProvider(AnonymousCredentialsProvider.create())));

        assertEquals(
                "This AWS SDK client carries no credentials, so the cache cannot be authenticated. "
                        + "A client built with AnonymousCredentialsProvider is not supported.",
                error.getMessage());
        assertTrue(cache.getAuthAttempts().isEmpty());
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
    }

    @Test
    void clientRejectsAClientWhoseCredentialsCannotBeResolved() {
        SdkClientException unresolved =
                SdkClientException.create("Unable to load credentials from any of the providers");
        AwsCredentialsProvider failing =
                () -> {
                    throw unresolved;
                };

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () -> cacheBuilder().install(appBuilder().credentialsProvider(failing)));

        assertEquals(
                "The AWS SDK client's credentials provider could not resolve credentials, so the cache "
                        + "cannot be authenticated: Unable to load credentials from any of the providers",
                error.getMessage());
        assertSame(unresolved, error.getCause());
        assertTrue(cache.getAuthAttempts().isEmpty());
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
    }

    @Test
    void clientRejectsAClientWithNoRegion() throws Exception {
        assumeTrue(
                System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null,
                "the environment supplies a region");
        Path emptyProfile = Files.createFile(tempDir.resolve("config"));
        Map<String, String> properties = new HashMap<>();
        properties.put("aws.configFile", emptyProfile.toString());
        properties.put("aws.sharedCredentialsFile", emptyProfile.toString());
        properties.put("aws.disableEc2Metadata", "true");
        withSystemProperties(
                properties,
                () -> {
                    DynamoDbClientBuilder noRegion =
                            DynamoDbClient.builder()
                                    .credentialsProvider(
                                            StaticCredentialsProvider.create(
                                                    AwsBasicCredentials.create(OWN_ACCESS_KEY, "own-secret-key")))
                                    .endpointOverride(URI.create("http://127.0.0.1:1"));

                    ElastiCacheIntegrationsConfigError error =
                            assertThrows(
                                    ElastiCacheIntegrationsConfigError.class, () -> cacheBuilder().install(noRegion));

                    assertEquals(
                            "The AWS SDK client has no region, so the cache's IAM token cannot be signed. "
                                    + "Create the client with a region.",
                            error.getMessage());
                    assertTrue(cache.getAuthAttempts().isEmpty());
                });
    }

    private static void withSystemProperties(Map<String, String> properties, Runnable body) {
        Map<String, String> previous = new HashMap<>();
        properties.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
        try {
            body.run();
        } finally {
            previous.forEach(
                    (key, value) -> {
                        if (value == null) {
                            System.clearProperty(key);
                        } else {
                            System.setProperty(key, value);
                        }
                    });
        }
    }

    @Test
    void clientRejectsMissingOptions() {
        assertThrows(
                ElastiCacheIntegrationsConfigError.class,
                () -> ElastiCacheIntegrationsClient.builder().cacheName("c").install(appBuilder()));
        assertThrows(
                ElastiCacheIntegrationsConfigError.class,
                () -> ElastiCacheIntegrationsClient.builder().endpoint("cache:6379").install(appBuilder()));
        assertThrows(
                ElastiCacheIntegrationsConfigError.class,
                () ->
                        ElastiCacheIntegrationsClient.builder()
                                .endpoint("no-port")
                                .cacheName("c")
                                .install(appBuilder()));
    }

    @Test
    void clientGivesSessionOwnershipOfManagedAuth() {
        DynamoDbClient client = cacheBuilder().install(appBuilder());
        ManagedIamAuthProvider auth = handle.get().getOwnedAuthProvider();
        assertNotNull(auth.get());

        client.close();

        assertThrows(ElastiCacheIntegrationsError.class, auth::get);
    }

    @Test
    void clientTreatsANullClientConfigurationAsAllDefaults() {
        replyWithTables("from-cache");

        try (DynamoDbClient client = cacheBuilder().clientConfiguration(null).install(appBuilder())) {
            assertEquals(Collections.singletonList("from-cache"), client.listTables().tableNames());
        }
    }

    @Test
    void clientPassesCacheRequestTimeoutToConnectorFactory() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("aws.request");
        RecordingOriginClient origin = new RecordingOriginClient();

        try (DynamoDbClient client =
                cacheBuilder()
                        .originHttpClient(origin)
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .cacheRequestTimeoutMs(100)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(Collections.emptyList(), client.listTables().tableNames());
        }

        assertNotNull(origin.request.get(), "the timed-out request fell back to the origin");
    }

    @Test
    void clientPassesTheCachePolicyTimeoutToTheSession() {
        try (DynamoDbClient client = cacheBuilder().install(appBuilder())) {
            assertEquals(3600, handle.get().getSession().stalenessDurationSeconds());
        }

        try (DynamoDbClient client =
                cacheBuilder()
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .cachePolicyTimeoutSeconds(600)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(600, handle.get().getSession().stalenessDurationSeconds());
        }
    }

    @Test
    void clientPassesConnectionTimeoutToTheSession() {
        try (DynamoDbClient client = cacheBuilder().clientConfiguration(null).install(appBuilder())) {
            assertEquals(5_000, handle.get().getSession().reconnectPolicy().getConnectionTimeoutMs());
        }

        try (DynamoDbClient client =
                cacheBuilder()
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .connectionTimeoutMs(1_500)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(1_500, handle.get().getSession().reconnectPolicy().getConnectionTimeoutMs());
        }
    }

    @Test
    void clientPassesTheFallbackDurationToTheSession() {
        try (DynamoDbClient client = cacheBuilder().install(appBuilder())) {
            assertTrue(handle.get().getSession().reconnectPolicy().getFallbackDurationSeconds() < 0);
        }

        try (DynamoDbClient client =
                cacheBuilder()
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .fallbackDurationSeconds(60)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(60, handle.get().getSession().reconnectPolicy().getFallbackDurationSeconds());
        }
    }

    @Test
    void clientPassesCachePolicyOverridesToTheSession() {
        Map<String, Object> overrides = Collections.singletonMap("item_ttl", 300);

        try (DynamoDbClient client =
                cacheBuilder()
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .cachePolicyOverrides(overrides)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(overrides, handle.get().getSession().overrides());
            assertNotNull(handle.get().getSession().getConfig());
        }
    }

    @Test
    void clientCopiesCachePolicyOverridesBeforePassingThemToTheSession() {
        List<Object> tables = new ArrayList<>(Collections.singletonList("t"));
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("cached_tables", tables);
        ElastiCacheIntegrationsClientConfiguration configuration =
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cachePolicyOverrides(overrides)
                        .build();

        try (DynamoDbClient client =
                cacheBuilder().clientConfiguration(configuration).install(appBuilder())) {
            tables.add("other");
            overrides.put("item_ttl", 1);

            Map<String, Object> sent = handle.get().getSession().overrides();
            assertEquals(Collections.singletonMap("cached_tables", Collections.singletonList("t")), sent);
            assertTrue(sent != overrides);
            assertTrue(sent.get("cached_tables") != tables);
        }
    }

    @Test
    void clientKeepsTheBuildersOverrideConfiguration() {
        AtomicBoolean ownInterceptorRan = new AtomicBoolean();
        ExecutionInterceptor ownInterceptor =
                new ExecutionInterceptor() {
                    @Override
                    public void beforeExecution(
                            Context.BeforeExecution context, ExecutionAttributes executionAttributes) {
                        ownInterceptorRan.set(true);
                    }
                };
        replyWithTables("t");

        try (DynamoDbClient client =
                cacheBuilder()
                        .install(
                                appBuilder()
                                        .overrideConfiguration(
                                                ClientOverrideConfiguration.builder()
                                                        .addExecutionInterceptor(ownInterceptor)
                                                        .apiCallTimeout(Duration.ofSeconds(30))
                                                        .build()))) {
            assertEquals(Collections.singletonList("t"), client.listTables().tableNames());

            assertTrue(ownInterceptorRan.get());
            assertEquals(
                    Duration.ofSeconds(30),
                    client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout().get());
        }
    }

    @Test
    void clientRejectsABuilderWithItsOwnHttpClient() {
        RecordingOriginClient ownTransport = new RecordingOriginClient();

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () -> cacheBuilder().install(appBuilder().httpClient(ownTransport)));

        assertTrue(error.getMessage().contains("originHttpClient"), error.getMessage());
        assertNotNull(error.getCause(), "the SDK's own error is the cause");
        assertEquals(0, ownTransport.closeCount.get(), "the application's client stays open");
        assertTrue(cache.getAuthAttempts().isEmpty());
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
    }

    @Test
    void clientRejectsABuilderWithItsOwnHttpClientBuilder() {
        RecordingOriginClientBuilder ownTransport = new RecordingOriginClientBuilder();
        DynamoDbClientBuilder builder = appBuilder().httpClientBuilder(ownTransport);

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class, () -> cacheBuilder().install(builder));

        assertTrue(error.getMessage().contains("originHttpClient"), error.getMessage());
        assertNull(handle.get(), "no cache connection is created");
        builder.build().close();
        assertEquals(1, ownTransport.buildCount.get(), "the application's builder keeps its transport");
    }

    @Test
    void clientRejectsABuilderItCannotInspect() {
        RecordingOriginClientBuilder ownTransport = new RecordingOriginClientBuilder();
        DynamoDbClientBuilder real = appBuilder().httpClientBuilder(ownTransport);
        DynamoDbClientBuilder wrapper =
                (DynamoDbClientBuilder)
                        Proxy.newProxyInstance(
                                DynamoDbClientBuilder.class.getClassLoader(),
                                new Class<?>[] {DynamoDbClientBuilder.class},
                                (proxy, method, args) -> {
                                    Object result = method.invoke(real, args);
                                    return result == real ? proxy : result;
                                });

        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class, () -> cacheBuilder().install(wrapper));

        assertTrue(error.getMessage().contains("originHttpClient"), error.getMessage());
        assertNull(handle.get(), "no cache connection is created");
        real.build().close();
        assertEquals(1, ownTransport.buildCount.get(), "the application's builder keeps its transport");
    }

    @Test
    void clientRejectsABuilderWhoseHttpSettingCannotBeRead() {
        ElastiCacheIntegrationsConfigError error =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () ->
                                ElastiCacheIntegrationsClient.rejectHttpClientBuilder(
                                        appBuilder(),
                                        SdkClientOption.CONFIGURED_SYNC_HTTP_CLIENT_BUILDER,
                                        "originHttpClient",
                                        "fieldThatDoesNotExist"));

        assertTrue(error.getMessage().contains("originHttpClient"), error.getMessage());
        assertNotNull(error.getCause(), "the reflection failure is the cause");
    }

    @Test
    void clientRejectsAnInvalidCacheNameOrUserIdBeforeConnecting() {
        ElastiCacheIntegrationsConfigError badName =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () -> cacheBuilder().cacheName("Bad Name!").install(appBuilder()));
        ElastiCacheIntegrationsConfigError badUser =
                assertThrows(
                        ElastiCacheIntegrationsConfigError.class,
                        () -> cacheBuilder().userId("bad user!").install(appBuilder()));

        assertTrue(badName.getMessage().contains("serverlessCacheName"), badName.getMessage());
        assertTrue(badUser.getMessage().contains("userId"), badUser.getMessage());

        assertNull(handle.get(), "no cache connection is created");
    }

    @Test
    void clientCloseClosesTheSession() {
        RecordingOriginClient origin = new RecordingOriginClient();
        DynamoDbClient client = cacheBuilder().originHttpClient(origin).install(appBuilder());
        assertTrue(handle.get().isOpen());

        client.close();

        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
        assertFalse(handle.get().isOpen());
        assertEquals(0, origin.closeCount.get(), "a caller-supplied origin stays open");
        client.close();
        assertEquals(0, origin.closeCount.get());
    }

    @Test
    void clientAcceptsTheApplicationsOwnTransportAsTheOrigin() {
        cache.setConfigJson("{\"version\":\"1.0\",\"refresh_interval\":60,\"predicates\":[]}");
        RecordingOriginClient ownTransport = new RecordingOriginClient();

        try (DynamoDbClient client =
                cacheBuilder().originHttpClient(ownTransport).install(appBuilder())) {
            assertEquals(Collections.emptyList(), client.listTables().tableNames());
        }

        assertNotNull(ownTransport.request.get(), "a request no predicate matches goes to the origin");
        assertTrue(cache.getRequests().isEmpty());
    }

    @Test
    void clientVerifiesTheCacheAgainstCaCerts() throws Exception {
        replyWithTables("verified");
        try (DynamoDbClient client = cacheBuilder().install(appBuilder())) {
            assertEquals(Collections.singletonList("verified"), client.listTables().tableNames());
        }

        // A bundle that does not hold the cache's certificate fails the connection.
        try (MockTlsServer other = new MockTlsServer(new MockCache())) {
            Path otherBundle = tempDir.resolve("other.pem");
            other.writeCaBundle(otherBundle);
            ElastiCacheIntegrationsError mismatch =
                    assertThrows(
                            ElastiCacheIntegrationsError.class,
                            () -> cacheBuilder().caCerts(otherBundle).install(appBuilder()));
            assertTrue(mismatch instanceof ElastiCacheIntegrationsConnectionError, mismatch.toString());
        }

        // Without caCerts the system trust store does not know the self-signed certificate.
        assertThrows(
                ElastiCacheIntegrationsConnectionError.class,
                () -> cacheBuilder().caCerts(null).install(appBuilder()));

        // The host name is still checked against the bundle's certificate (CN=localhost).
        assertThrows(
                ElastiCacheIntegrationsConnectionError.class,
                () -> cacheBuilder().endpoint("127.0.0.1:" + server.getPort()).install(appBuilder()));

        // verify(false) skips both checks.
        try (DynamoDbClient client =
                cacheBuilder()
                        .caCerts(null)
                        .endpoint("127.0.0.1:" + server.getPort())
                        .verify(false)
                        .install(appBuilder())) {
            assertEquals(Collections.singletonList("verified"), client.listTables().tableNames());
        }
    }

    @Test
    void clientRejectsAnUnusableCaCertsFile() throws Exception {
        Path missing = tempDir.resolve("missing.pem");
        Path garbage = tempDir.resolve("garbage.pem");
        Files.write(garbage, "not a certificate".getBytes(StandardCharsets.US_ASCII));

        for (Path bad : Arrays.asList(missing, garbage)) {
            assertThrows(
                    ElastiCacheIntegrationsConfigError.class,
                    () -> cacheBuilder().caCerts(bad).install(appBuilder()),
                    bad.toString());
        }
        assertTrue(cache.getAuthAttempts().isEmpty());
    }

    @Test
    void builderConnectionFailurePropagatesWithoutCallingOrigin() throws Exception {
        int cachePort = server.getPort();
        server.close();
        RecordingOriginClient origin = new RecordingOriginClient();

        assertThrows(
                ElastiCacheIntegrationsError.class,
                () ->
                        cacheBuilder()
                                .endpoint("localhost:" + cachePort)
                                .originHttpClient(origin)
                                .install(appBuilder()));

        assertNull(origin.request.get());
        assertEquals(0, origin.closeCount.get(), "caller-managed origin must remain open");
        assertEquals(ElastiCacheIntegrationsSession.State.CLOSED, handle.get().getSession().getState());
    }

    @Test
    void clientOperationReusesCapturedRequestForSdkRetry() {
        AtomicInteger attempt = new AtomicInteger();
        cache.setReplyFunction(
                request ->
                        attempt.getAndIncrement() == 0
                                ? Reply.ok(
                                        500,
                                        headers("x-amzn-errortype", "InternalServerError"),
                                        "{\"__type\":\"InternalServerError\",\"message\":\"retry\"}")
                                : Reply.ok(
                                        200,
                                        Collections.singletonMap("content-type", "application/x-amz-json-1.0"),
                                        "{\"TableNames\":[]}"));

        try (DynamoDbClient client = cacheBuilder().install(appBuilder())) {
            assertEquals(Collections.emptyList(), client.listTables().tableNames());
        }

        assertEquals(2, cache.getRequests().size());
    }

    @Test
    void clientOperationCacheTimeoutFallsBackToOrigin() {
        cache.setFailureMode(MockCache.FailureMode.HANG);
        cache.setFailureCommand("aws.request");
        RecordingOriginClient origin = new RecordingOriginClient();

        try (DynamoDbClient client =
                cacheBuilder()
                        .originHttpClient(origin)
                        .clientConfiguration(
                                ElastiCacheIntegrationsClientConfiguration.builder()
                                        .cacheRequestTimeoutMs(100)
                                        .build())
                        .install(appBuilder())) {
            assertEquals(Collections.emptyList(), client.listTables().tableNames());
        }

        assertNotNull(origin.request.get());
        assertTrue(origin.request.get().httpRequest().firstMatchingHeader("Authorization").isPresent());
    }

    @Test
    void syncClientUsesRecoveredConnectionThroughProxy() throws Exception {
        replyWithTables("reconnected");
        RecordingOriginClient origin = new RecordingOriginClient();

        try (MockTcpProxy proxy = new MockTcpProxy("localhost", server.getPort());
                DynamoDbClient client =
                        cacheBuilder()
                                .endpoint("localhost:" + proxy.getPort())
                                .originHttpClient(origin)
                                .install(appBuilder())) {
            proxy.disconnectClients();

            awaitRecovered(handle.get().getSession());
            assertEquals(Collections.singletonList("reconnected"), client.listTables().tableNames());
            assertEquals(2, cache.getAuthAttempts().size());
            assertEquals(2, proxy.getAcceptedConnections());
        }
    }

    private static final class RecordingOriginClientBuilder
            implements SdkHttpClient.Builder<RecordingOriginClientBuilder> {
        private final AtomicInteger buildCount = new AtomicInteger();

        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            buildCount.incrementAndGet();
            return new RecordingOriginClient();
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

    private void awaitRecovered(ElastiCacheIntegrationsSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((cache.getAuthAttempts().size() < 2
                        || session.getState() != ElastiCacheIntegrationsSession.State.OPEN)
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(2, cache.getAuthAttempts().size());
        assertEquals(ElastiCacheIntegrationsSession.State.OPEN, session.getState());
    }

    private static Map<String, String> headers(String key, String value) {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/x-amz-json-1.0");
        headers.put(key, value);
        return headers;
    }
}
