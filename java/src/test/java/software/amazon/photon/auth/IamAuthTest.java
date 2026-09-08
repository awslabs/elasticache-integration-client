package software.amazon.photon.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.photon.exception.PhotonError;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IAM authentication: parameter validation, token format, and error handling.
 */
class IamAuthTest {

    // AWS documentation example credentials (intentionally fake)
    private static final String EXAMPLE_ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE";
    private static final String EXAMPLE_SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String EXAMPLE_SESSION_TOKEN = "FwoGZXIvYXdzEBYaDHqa0APexampletoken";

    private static AwsCredentialsProvider staticCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY));
    }

    private static AwsCredentialsProvider sessionCredentials() {
        return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY, EXAMPLE_SESSION_TOKEN));
    }

    // =========================================================================
    // IamAuthTokenGenerator tests
    // =========================================================================

    @Nested
    class IamAuthTokenGeneratorTest {

        @Test
        void generateToken_createsSchemeFreeSigV4Token() {
            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .userId("my-user")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            String token = generator.generateToken();

            // Token should have http:// stripped and start with cache name
            assertFalse(token.startsWith("http://"), "Token should not start with http://");
            assertTrue(token.startsWith("my-cache/"), "Token should start with cache name");

            // Must contain the expected query params
            assertTrue(token.contains("Action=connect"), "Token should contain Action=connect");
            assertTrue(token.contains("User=my-user"), "Token should contain User=my-user");
            assertTrue(token.contains("ResourceType=ServerlessCache"),
                    "Token should contain ResourceType=ServerlessCache");

            // Must contain SigV4 signing params
            assertTrue(token.contains("X-Amz-Credential="), "Token should contain X-Amz-Credential");
            assertTrue(token.contains("X-Amz-Signature="), "Token should contain X-Amz-Signature");
            assertTrue(token.contains("X-Amz-Expires=900"), "Token should contain X-Amz-Expires=900");
            assertTrue(token.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"),
                    "Token should contain algorithm");
        }

        @Test
        void generateToken_withSessionCredentials_includesSecurityToken() {
            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(sessionCredentials())
                    .build();

            String token = generator.generateToken();

            assertTrue(token.contains("X-Amz-Security-Token="),
                    "Token should contain security token for session credentials");
        }

        @Test
        void generateToken_usesDefaultUserId() {
            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            String token = generator.generateToken();

            assertTrue(token.contains("User=" + IamAuthTokenGenerator.DEFAULT_USER_ID),
                    "Token should use default user ID when not specified");
            assertEquals(IamAuthTokenGenerator.DEFAULT_USER_ID, generator.getUserId());
        }

        @Test
        void generateToken_emptyUserIdUsesDefault() {
            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .userId("")
                    .build();

            String token = generator.generateToken();

            assertTrue(token.contains("User=" + IamAuthTokenGenerator.DEFAULT_USER_ID),
                    "Token should use default user ID when empty string specified");
            assertEquals(IamAuthTokenGenerator.DEFAULT_USER_ID, generator.getUserId());
        }

        @Test
        void generateToken_customExpiresIn() {
            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .expiresIn(Duration.ofSeconds(300))
                    .build();

            String token = generator.generateToken();

            assertTrue(token.contains("X-Amz-Expires=300"),
                    "Token should use custom expiry");
        }

        @Test
        void generateToken_differentRegions() {
            for (Region region : new Region[]{Region.US_EAST_1, Region.EU_WEST_1, Region.AP_SOUTHEAST_2}) {
                IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                        .cacheName("my-cache")
                        .region(region)
                        .credentialsProvider(staticCredentials())
                        .build();

                String token = generator.generateToken();

                assertTrue(token.contains(region.id()),
                        "Token should contain region: " + region.id());
            }
        }

        @Test
        void generateToken_throwsOnNullCredentials() {
            AwsCredentialsProvider nullProvider = () -> null;

            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(nullProvider)
                    .build();

            PhotonError error = assertThrows(PhotonError.class, generator::generateToken);
            assertTrue(error.getMessage().contains("No AWS credentials"),
                    "Error should mention missing credentials");
        }

        @Test
        void generateToken_throwsOnCredentialProviderException() {
            AwsCredentialsProvider failingProvider = () -> {
                throw new RuntimeException("Credential resolution failed");
            };

            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(failingProvider)
                    .build();

            PhotonError error = assertThrows(PhotonError.class, generator::generateToken);
            assertTrue(error.getMessage().contains("No AWS credentials"),
                    "Error should mention missing credentials");
            assertNotNull(error.getCause(), "Error should have cause");
        }

        @Test
        void generateToken_errorDoesNotLeakCredentials() {
            // Simulate an error that might contain credential material
            AwsCredentialsProvider failingProvider = () -> {
                throw new RuntimeException("Error with secret=" + EXAMPLE_SECRET_KEY);
            };

            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(failingProvider)
                    .build();

            PhotonError error = assertThrows(PhotonError.class, generator::generateToken);

            // The error message should not contain the secret key
            assertFalse(error.getMessage().contains(EXAMPLE_SECRET_KEY),
                    "Error message should not contain credential material");
        }

        @Test
        void generateToken_freshCredentialsEachCall() {
            AtomicInteger callCount = new AtomicInteger(0);
            AwsCredentialsProvider countingProvider = () -> {
                callCount.incrementAndGet();
                return AwsBasicCredentials.create(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY);
            };

            IamAuthTokenGenerator generator = IamAuthTokenGenerator.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(countingProvider)
                    .build();

            generator.generateToken();
            generator.generateToken();
            generator.generateToken();

            assertEquals(3, callCount.get(),
                    "Credentials should be resolved fresh on each call");
        }

        // --- Builder validation tests ---

        @Test
        void builder_rejectsNullCacheName() {
            assertThrows(NullPointerException.class, () ->
                    IamAuthTokenGenerator.builder()
                            .region(Region.US_EAST_1)
                            .credentialsProvider(staticCredentials())
                            .build());
        }

        @Test
        void builder_rejectsEmptyCacheName() {
            assertThrows(IllegalArgumentException.class, () ->
                    IamAuthTokenGenerator.builder()
                            .cacheName("")
                            .region(Region.US_EAST_1)
                            .credentialsProvider(staticCredentials())
                            .build());
        }

        @Test
        void builder_rejectsNullRegion() {
            assertThrows(NullPointerException.class, () ->
                    IamAuthTokenGenerator.builder()
                            .cacheName("my-cache")
                            .credentialsProvider(staticCredentials())
                            .build());
        }

        @Test
        void builder_rejectsNullCredentialsProvider() {
            assertThrows(NullPointerException.class, () ->
                    IamAuthTokenGenerator.builder()
                            .cacheName("my-cache")
                            .region(Region.US_EAST_1)
                            .build());
        }
    }

    // =========================================================================
    // IamAuthTokenProvider tests
    // =========================================================================

    @Nested
    class IamAuthTokenProviderTest {

        @Test
        void get_returnsUserIdAndToken() {
            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .userId("my-user")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            AuthCredentials creds = provider.get();

            assertEquals("my-user", creds.getUserId());
            assertNotNull(creds.getToken());
            assertTrue(creds.getToken().contains("Action=connect"));
        }

        @Test
        void get_mintsNewTokenEachCall() {
            AtomicInteger callCount = new AtomicInteger(0);
            AwsCredentialsProvider countingProvider = () -> {
                callCount.incrementAndGet();
                return AwsBasicCredentials.create(EXAMPLE_ACCESS_KEY, EXAMPLE_SECRET_KEY);
            };

            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(countingProvider)
                    .build();

            provider.get();
            provider.get();
            provider.get();

            assertEquals(3, callCount.get(),
                    "Each get() should mint a fresh token");
        }

        @Test
        void get_usesDefaultUserId() {
            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            AuthCredentials creds = provider.get();

            assertEquals(IamAuthTokenGenerator.DEFAULT_USER_ID, creds.getUserId());
        }

        @Test
        void create_withCacheAndRegion() {
            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .region(Region.EU_WEST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            assertEquals("my-cache", provider.getCacheName());
            assertEquals(Region.EU_WEST_1, provider.getRegion());
            assertEquals(IamAuthTokenGenerator.DEFAULT_USER_ID, provider.getUserId());
        }

        @Test
        void builder_rejectsNullCacheName() {
            assertThrows(NullPointerException.class, () ->
                    IamAuthTokenProvider.builder()
                            .region(Region.US_EAST_1)
                            .build());
        }

        @Test
        void builder_rejectsNullRegion() {
            assertThrows(NullPointerException.class, () ->
                    IamAuthTokenProvider.builder()
                            .cacheName("my-cache")
                            .build());
        }

        @Test
        void toString_doesNotContainToken() {
            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            String str = provider.toString();

            assertTrue(str.contains("my-cache"));
            assertTrue(str.contains("us-east-1"));
            // Should not contain any secret material
            assertFalse(str.contains(EXAMPLE_SECRET_KEY));
            assertFalse(str.contains("X-Amz-Signature"));
        }
    }

    // =========================================================================
    // AuthCredentials tests
    // =========================================================================

    @Nested
    class AuthCredentialsTest {

        @Test
        void constructor_storesValues() {
            AuthCredentials creds = new AuthCredentials("user", "token123");

            assertEquals("user", creds.getUserId());
            assertEquals("token123", creds.getToken());
        }

        @Test
        void constructor_rejectsNullUserId() {
            assertThrows(NullPointerException.class, () ->
                    new AuthCredentials(null, "token"));
        }

        @Test
        void constructor_rejectsNullToken() {
            assertThrows(NullPointerException.class, () ->
                    new AuthCredentials("user", null));
        }

        @Test
        void toString_doesNotContainToken() {
            AuthCredentials creds = new AuthCredentials("user", "secret-token-value");

            String str = creds.toString();

            assertTrue(str.contains("user"));
            assertFalse(str.contains("secret-token-value"),
                    "toString should not expose token");
        }

        @Test
        void equals_samValues() {
            AuthCredentials creds1 = new AuthCredentials("user", "token");
            AuthCredentials creds2 = new AuthCredentials("user", "token");

            assertEquals(creds1, creds2);
            assertEquals(creds1.hashCode(), creds2.hashCode());
        }

        @Test
        void equals_differentValues() {
            AuthCredentials creds1 = new AuthCredentials("user1", "token");
            AuthCredentials creds2 = new AuthCredentials("user2", "token");
            AuthCredentials creds3 = new AuthCredentials("user1", "different");

            assertNotEquals(creds1, creds2);
            assertNotEquals(creds1, creds3);
        }
    }

    // =========================================================================
    // Integration with PhotonConnector tests
    // =========================================================================

    @Nested
    class IntegrationTest {

        @Test
        void provider_canBeUsedWithPhotonConnection() {
            // This test verifies the API shape works with PhotonConnector
            // without actually connecting (no mock server needed)

            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .userId("my-user")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            // The provider implements Supplier<AuthCredentials>
            // PhotonConnector.connectAndAuthenticate accepts Supplier<String> for the token
            // So we can use it like this:
            AuthCredentials creds = provider.get();

            // Verify we get the expected structure for AUTH command
            assertEquals("my-user", creds.getUserId());
            assertNotNull(creds.getToken());
            assertTrue(creds.getToken().length() > 100, "Token should be a substantial presigned URL");
        }

        @Test
        void provider_asSupplierForConnectionAuth() {
            // Demonstrates how to use with PhotonConnector's Supplier<String> API
            IamAuthTokenProvider provider = IamAuthTokenProvider.builder()
                    .cacheName("my-cache")
                    .region(Region.US_EAST_1)
                    .credentialsProvider(staticCredentials())
                    .build();

            // This is how you'd pass it to PhotonConnector:
            // conn.connectAndAuthenticate(() -> provider.get().getToken());
            java.util.function.Supplier<String> tokenSupplier = () -> provider.get().getToken();

            String token = tokenSupplier.get();
            assertTrue(token.contains("X-Amz-Signature="));
        }
    }
}
