package software.amazon.elasticache.integrations.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Cache compatibility adapter for the ElastiCache Java IAM toolkit.
 *
 * <p>Input validation, normalization, credential resolution, and SigV4 signing are delegated to
 * {@link ElastiCacheIamAuthTokenProvider}. Cache keeps this class so existing callers can continue
 * to supply it through the established authentication API.
 *
 * <p>Example token format:
 *
 * <pre>{@code
 * my-cache/?Action=connect&User=default&ResourceType=ServerlessCache
 * &X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...&X-Amz-Date=...
 * &X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=...
 * }</pre>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * String token = IamAuthTokenGenerator.builder()
 *     .cacheName("my-cache")
 *     .region(Region.US_EAST_1)
 *     .credentialsProvider(DefaultCredentialsProvider.create())
 *     .build()
 *     .generateToken();
 * }</pre>
 */
public final class IamAuthTokenGenerator {

    /** SigV4 presign expiry fixed by the ElastiCache IAM toolkit contract. */
    public static final Duration TOKEN_EXPIRY = Duration.ofMinutes(15);

    /** Default ElastiCache IAM user for serverless caches. */
    public static final String DEFAULT_USER_ID = "default.iam-user";

    private final String cacheName;
    private final String userId;
    private final Region region;
    private final ElastiCacheIamAuthTokenProvider toolkitTokenProvider;

    private IamAuthTokenGenerator(Builder builder) {
        String requestedCacheName = Objects.requireNonNull(builder.cacheName, "cacheName is required");
        String requestedUserId =
                builder.userId != null && !builder.userId.isEmpty() ? builder.userId : DEFAULT_USER_ID;
        this.region = Objects.requireNonNull(builder.region, "region is required");
        AwsCredentialsProvider credentialsProvider =
                Objects.requireNonNull(builder.credentialsProvider, "credentialsProvider is required");

        Duration expiresIn = builder.expiresIn != null ? builder.expiresIn : TOKEN_EXPIRY;
        if (!TOKEN_EXPIRY.equals(expiresIn)) {
            throw new ElastiCacheIntegrationsError(
                    "expiresIn must be exactly 900 seconds for ElastiCache IAM authentication");
        }
        if (builder.clock != null) {
            throw new ElastiCacheIntegrationsError(
                    "clock customization is not supported by the ElastiCache IAM toolkit");
        }

        try {
            this.toolkitTokenProvider =
                    ElastiCacheIamAuthTokenProvider.builder()
                            .serverlessCacheName(requestedCacheName)
                            .userId(requestedUserId)
                            .region(region)
                            .credentialsProvider(credentialsProvider)
                            .build();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to initialize IAM authentication", e);
        }
        this.cacheName = requestedCacheName.toLowerCase(Locale.ROOT);
        this.userId = toolkitTokenProvider.getUserId();
    }

    /**
     * Generate a fresh IAM auth token.
     *
     * <p>The toolkit resolves credentials and signs a new token on every call so rotated credentials
     * are picked up automatically. See the <a
     * href="https://github.com/aws/developer-toolkit-elasticache/tree/main/java#caching-and-background-refresh">
     * toolkit token manager documentation</a> for its validity contract.
     *
     * @return the signed token string (presigned URL with scheme stripped)
     * @throws ElastiCacheIntegrationsError if credential resolution or signing fails
     */
    public String generateToken() {
        try {
            return toolkitTokenProvider.getToken();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to generate IAM auth token", e);
        }
    }

    /**
     * Get the user ID that will be used in generated tokens.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Get the cache name.
     *
     * @return the ElastiCache serverless cache name
     */
    public String getCacheName() {
        return cacheName;
    }

    /**
     * Get the region.
     *
     * @return the AWS region
     */
    public Region getRegion() {
        return region;
    }

    /**
     * Create a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "IamAuthTokenGenerator{"
                + "cacheName='"
                + cacheName
                + '\''
                + ", userId='"
                + userId
                + '\''
                + ", region="
                + region
                + '}';
    }

    /** Builder for {@link IamAuthTokenGenerator}. */
    public static final class Builder {
        private String cacheName;
        private String userId;
        private Region region;
        private AwsCredentialsProvider credentialsProvider;
        private Duration expiresIn;
        private Clock clock;

        private Builder() {}

        /**
         * Set the cache name (required).
         *
         * @param cacheName the ElastiCache serverless cache name
         * @return this builder
         */
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }

        /**
         * Set the user ID (optional, defaults to {@link IamAuthTokenGenerator#DEFAULT_USER_ID}).
         *
         * @param userId the IAM user ID
         * @return this builder
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Set the AWS region (required).
         *
         * @param region the AWS region
         * @return this builder
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * Set the credentials provider (required).
         *
         * @param credentialsProvider the AWS credentials provider
         * @return this builder
         */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        /**
         * Set the token expiration duration.
         *
         * <p>Retained for source compatibility. The ElastiCache toolkit owns the token lifetime, so
         * unsupported values are rejected. See the <a
         * href="https://github.com/aws/developer-toolkit-elasticache/tree/main/java#caching-and-background-refresh">
         * toolkit token manager documentation</a>.
         *
         * @param expiresIn the expiration duration
         * @return this builder
         */
        public Builder expiresIn(Duration expiresIn) {
            this.expiresIn = expiresIn;
            return this;
        }

        /**
         * Set the clock for signing.
         *
         * <p>Retained for source compatibility. The toolkit owns signing time, so setting a custom
         * clock is rejected at build time.
         *
         * @param clock the clock to use
         * @return this builder
         */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /**
         * Build the token generator.
         *
         * @return a new IamAuthTokenGenerator
         * @throws NullPointerException if required fields are null
         * @throws IllegalArgumentException if cacheName is empty
         */
        public IamAuthTokenGenerator build() {
            return new IamAuthTokenGenerator(this);
        }
    }
}
