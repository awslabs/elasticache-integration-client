package software.amazon.elasticache.integrations.auth;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Supplies fresh IAM authentication credentials for Cache cache connections.
 *
 * <p>Each call to {@link #get()} generates a fresh SigV4-presigned token, resolving credentials
 * anew so that rotated credentials (e.g., from STS or IMDS) are picked up automatically.
 *
 * <p>This provider implements {@code Supplier<AuthCredentials>} and can be used directly with
 * {@link software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector}.
 * When passed directly to {@code ElastiCacheIntegrationsClient}, {@code
 * ElastiCacheIntegrationsHandle}, or {@code ElastiCacheIntegrationsAsyncHandle}, including through
 * a {@code Supplier} reference, Cache creates and owns an auto-refreshing manager from this
 * configuration.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * IamAuthTokenProvider authProvider = IamAuthTokenProvider.builder()
 *     .cacheName("my-cache")
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * ElastiCacheIntegrationsConnector conn = new ElastiCacheIntegrationsConnector(host, port, sslContext);
 * conn.connectAndAuthenticate(authProvider);
 * }</pre>
 *
 * <p>Or using the convenience factory:
 *
 * <pre>{@code
 * Supplier<AuthCredentials> authProvider = IamAuthTokenProvider.create("my-cache", Region.US_EAST_1);
 * }</pre>
 */
public final class IamAuthTokenProvider implements Supplier<AuthCredentials> {

    private final IamAuthTokenGenerator generator;
    private final AwsCredentialsProvider credentialsProvider;
    private final Duration refreshAfter;
    private final Consumer<String> onTokenChanged;

    private IamAuthTokenProvider(
            IamAuthTokenGenerator generator,
            AwsCredentialsProvider credentialsProvider,
            Duration refreshAfter,
            Consumer<String> onTokenChanged) {
        this.generator = generator;
        this.credentialsProvider = credentialsProvider;
        this.refreshAfter = refreshAfter;
        this.onTokenChanged = onTokenChanged;
    }

    /**
     * Generate fresh authentication credentials.
     *
     * <p>Each call resolves credentials and mints a new token. The toolkit owns the token validity
     * contract.
     *
     * @return the user ID and token for the {@code HELLO 3 AUTH} handshake
     */
    @Override
    public AuthCredentials get() {
        String token = generator.generateToken();
        return new AuthCredentials(generator.getUserId(), token);
    }

    /** Get the user ID that will be used in generated credentials. */
    public String getUserId() {
        return generator.getUserId();
    }

    /** Get the cache name. */
    public String getCacheName() {
        return generator.getCacheName();
    }

    /** Get the region. */
    public Region getRegion() {
        return generator.getRegion();
    }

    /**
     * Create an independent toolkit auto-refresh manager from this provider's configuration.
     *
     * <p>Cache clients and handles call this automatically when this provider is passed to {@code
     * authProvider(...)}.
     */
    public ElastiCacheIamAuthTokenManager newTokenManager() {
        ElastiCacheIamAuthTokenManager.Builder managerBuilder =
                ElastiCacheIamAuthTokenManager.builder()
                        .serverlessCacheName(getCacheName())
                        .userId(getUserId())
                        .region(getRegion())
                        .credentialsProvider(credentialsProvider);
        if (refreshAfter != null) {
            managerBuilder.refreshAfter(refreshAfter);
        }
        if (onTokenChanged != null) {
            managerBuilder.onTokenChanged(onTokenChanged);
        }
        try {
            return managerBuilder.build();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to initialize IAM token auto-refresh", e);
        }
    }

    /**
     * Create a provider with default credentials and specified cache/region.
     *
     * <p>Uses {@link DefaultCredentialsProvider} which checks environment variables, system
     * properties, AWS profile, container credentials, and instance metadata.
     *
     * @param cacheName the ElastiCache serverless cache name
     * @param region the AWS region
     * @return a new provider
     */
    public static IamAuthTokenProvider create(String cacheName, Region region) {
        return builder().cacheName(cacheName).region(region).build();
    }

    /**
     * Create a provider with default credentials and default user ID.
     *
     * <p>The region is resolved from the default AWS region provider chain (environment, config file,
     * etc.).
     *
     * @param cacheName the ElastiCache serverless cache name
     * @return a new provider
     * @throws IllegalStateException if region cannot be resolved
     */
    public static IamAuthTokenProvider create(String cacheName) {
        Region region;
        try {
            region = DefaultAwsRegionProviderChain.builder().build().getRegion();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Could not resolve AWS region from default provider chain", e);
        }
        if (region == null) {
            throw new IllegalStateException("Could not resolve AWS region from default provider chain");
        }
        return create(cacheName, region);
    }

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "IamAuthTokenProvider{"
                + "cacheName='"
                + getCacheName()
                + '\''
                + ", userId='"
                + getUserId()
                + '\''
                + ", region="
                + getRegion()
                + '}';
    }

    /** Builder for {@link IamAuthTokenProvider}. */
    public static final class Builder {
        private String cacheName;
        private String userId;
        private Region region;
        private AwsCredentialsProvider credentialsProvider;
        private Duration expiresIn;
        private Duration refreshAfter;
        private Consumer<String> onTokenChanged;

        private Builder() {}

        /**
         * Set the cache name (required).
         *
         * @param cacheName the ElastiCache serverless cache name
         */
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }

        /**
         * Set the user ID (optional, defaults to "default").
         *
         * @param userId the IAM user ID
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Set the AWS region (required unless using region from default chain).
         *
         * @param region the AWS region
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * Set the credentials provider (optional, defaults to DefaultCredentialsProvider).
         *
         * @param credentialsProvider the AWS credentials provider
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
         */
        public Builder expiresIn(Duration expiresIn) {
            this.expiresIn = expiresIn;
            return this;
        }

        /**
         * Set how long after issuance Cache-managed tokens refresh.
         *
         * <p>The accepted range, default, retry behavior, and safety margin are defined by the <a
         * href="https://github.com/aws/developer-toolkit-elasticache/tree/main/java#caching-and-background-refresh">
         * toolkit token manager documentation</a>.
         */
        public Builder refreshAfter(Duration refreshAfter) {
            this.refreshAfter = refreshAfter;
            return this;
        }

        /** Set a callback invoked after a Cache-managed token is installed. */
        public Builder onTokenChanged(Consumer<String> onTokenChanged) {
            this.onTokenChanged = onTokenChanged;
            return this;
        }

        /**
         * Build the provider.
         *
         * @return a new IamAuthTokenProvider
         * @throws NullPointerException if required fields are null
         * @throws IllegalArgumentException if cacheName is empty
         */
        public IamAuthTokenProvider build() {
            Objects.requireNonNull(cacheName, "cacheName is required");
            Objects.requireNonNull(region, "region is required");

            AwsCredentialsProvider creds =
                    credentialsProvider != null
                            ? credentialsProvider
                            : DefaultCredentialsProvider.builder().build();

            IamAuthTokenGenerator.Builder generatorBuilder =
                    IamAuthTokenGenerator.builder()
                            .cacheName(cacheName)
                            .region(region)
                            .credentialsProvider(creds);

            if (userId != null && !userId.isEmpty()) {
                generatorBuilder.userId(userId);
            }
            if (expiresIn != null) {
                generatorBuilder.expiresIn(expiresIn);
            }

            return new IamAuthTokenProvider(
                    generatorBuilder.build(), creds, refreshAfter, onTokenChanged);
        }
    }
}
