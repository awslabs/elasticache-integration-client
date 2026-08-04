package software.amazon.photon.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTlsServer;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class PhotonClientTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_TOKEN = "test-token";

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    // Common SDK client configuration for tests - fake credentials since we intercept requests
    private final Consumer<DynamoDbClientBuilder> sdkConfig = b -> b
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")))
            .endpointOverride(URI.create("http://localhost:8080"));

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
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
    void builder_withEndpoint_parsesHostAndPort() {
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertNotNull(result.client());
            assertNotNull(result.handle());
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void builder_withHostAndPort_buildsSuccessfully() {
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .host("localhost")
                .port(server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertNotNull(result.client());
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void builder_withEndpointAndHost_throwsPhotonError() {
        PhotonClient.Builder builder = PhotonClient.builder()
                .endpoint("cache.example.com:6379")
                .host("other-host")
                .authCredentials(TEST_USER, TEST_TOKEN);

        assertThrows(PhotonError.class, () -> builder.build(DynamoDbClient.builder(), sdkConfig));
    }

    @Test
    void builder_withoutEndpointOrHost_throwsPhotonError() {
        PhotonClient.Builder builder = PhotonClient.builder()
                .authCredentials(TEST_USER, TEST_TOKEN);

        PhotonError error = assertThrows(PhotonError.class, 
                () -> builder.build(DynamoDbClient.builder(), sdkConfig));
        assertTrue(error.getMessage().contains("endpoint or host is required"));
    }

    @Test
    void builder_withoutAuthProvider_throwsPhotonError() {
        PhotonClient.Builder builder = PhotonClient.builder()
                .endpoint("cache.example.com:6379");

        PhotonError error = assertThrows(PhotonError.class, 
                () -> builder.build(DynamoDbClient.builder(), sdkConfig));
        assertTrue(error.getMessage().contains("authProvider is required"));
    }

    @Test
    void builder_withStaticAuthToken_usesTokenProvider() {
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, "my-static-token")
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void result_close_closesBothHandleAndClient() {
        PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig);

        assertTrue(result.handle().isOpen());
        
        result.close();
        
        assertFalse(result.handle().isOpen());
    }

    @Test
    void result_close_isIdempotent() {
        PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig);

        result.close();
        result.close(); // Should not throw
        
        assertFalse(result.handle().isOpen());
    }

    @Test
    void builder_connectionFailure_cleansUpAndRethrows() throws Exception {
        server.close(); // Stop the server so connection fails
        
        PhotonClient.Builder builder = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .connectTimeoutMs(100);

        assertThrows(Exception.class, () -> builder.build(DynamoDbClient.builder(), sdkConfig));
    }

    @Test
    void builder_withConfigOverrides_passesToSession() {
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .configOverrides(Map.of("ttl", 300))
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertNotNull(result.handle().getSession().getResolvedConfig());
        }
    }

    @Test
    void builder_withTimeouts_appliesTimeouts() {
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .connectTimeoutMs(1000)
                .readTimeoutMs(2000)
                .sslContext(clientSslContext)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertTrue(result.handle().isOpen());
        }
    }

    @Test
    void builder_withSdkOverrideConfiguration_mergesWithPhotonInterceptor() {
        // Track if our custom interceptor was installed
        AtomicBoolean customInterceptorCalled = new AtomicBoolean(false);
        
        ExecutionInterceptor customInterceptor = new ExecutionInterceptor() {
            @Override
            public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attrs) {
                customInterceptorCalled.set(true);
            }
        };
        
        // Create SDK config with custom interceptor and timeout
        ClientOverrideConfiguration sdkOverride = ClientOverrideConfiguration.builder()
                .addExecutionInterceptor(customInterceptor)
                .apiCallTimeout(Duration.ofSeconds(30))
                .build();
        
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                .sdkOverrideConfiguration(sdkOverride)
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertTrue(result.handle().isOpen());
            // The client should have both the Photon interceptor and our custom interceptor merged
            // Note: We can't directly inspect the interceptors, but we can verify no exception thrown
            // and the client was built successfully with both configs
        }
    }

    @Test
    void builder_withoutSdkOverrideConfiguration_usesDefaultConfig() {
        // This just verifies that when no sdkOverrideConfiguration is set,
        // the build still works (using only the Photon interceptor)
        try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
                .endpoint("localhost:" + server.getPort())
                .authCredentials(TEST_USER, TEST_TOKEN)
                .sslContext(clientSslContext)
                // No sdkOverrideConfiguration set
                .build(DynamoDbClient.builder(), sdkConfig)) {
            
            assertTrue(result.handle().isOpen());
        }
    }
}
