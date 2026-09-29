package software.amazon.photon.auth;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies fresh IAM authentication credentials for Photon cache connections.
 *
 * <p>Each call to {@link #get()} generates a fresh SigV4-presigned token,
 * resolving credentials anew so that rotated credentials (e.g., from STS
 * or IMDS) are picked up automatically.
 *
 * <p>This provider implements {@code Supplier<AuthCredentials>} and can be
 * used directly with {@link software.amazon.photon.connection.PhotonConnector}.
 *
 * <p>Usage:
 * <pre>{@code
 * IamAuthTokenProvider authProvider = IamAuthTokenProvider.builder()
 *     .cacheName("my-cache")
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * PhotonConnector conn = new PhotonConnector(host, port, sslContext);
 * conn.connectAndAuthenticate(authProvider);
 * }</pre>
 *
 * <p>Or using the convenience factory:
 * <pre>{@code
 * Supplier<AuthCredentials> authProvider = IamAuthTokenProvider.create("my-cache", Region.US_EAST_1);
 * }</pre>
 */
public final class IamAuthTokenProvider implements Supplier<AuthCredentials> {

    private final IamAuthTokenGenerator generator;

    private IamAuthTokenProvider(IamAuthTokenGenerator generator) {
        this.generator = generator;
    }

    /**
     * Generate fresh authentication credentials.
     *
     * <p>Each call resolves credentials and mints a new token. The token
     * is valid for 15 minutes but should be used immediately.
     *
     * @return the user ID and token for the {@code HELLO 3 AUTH} handshake
     */
    @Override
    public AuthCredentials get() {
        String token = generator.generateToken();
        return new AuthCredentials(generator.getUserId(), token);
    }

    /**
     * Get the user ID that will be used in generated credentials.
     */
    public String getUserId() {
        return generator.getUserId();
    }

    /**
     * Get the cache name.
     */
    public String getCacheName() {
        return generator.getCacheName();
    }

    /**
     * Get the region.
     */
    public Region getRegion() {
        return generator.getRegion();
    }

    /**
     * Create a provider with default credentials and specified cache/region.
     *
     * <p>Uses {@link DefaultCredentialsProvider} which checks environment
     * variables, system properties, AWS profile, container credentials,
     * and instance metadata.
     *
     * @param cacheName the ElastiCache serverless cache name
     * @param region    the AWS region
     * @return a new provider
     */
    public static IamAuthTokenProvider create(String cacheName, Region region) {
        return builder()
                .cacheName(cacheName)
                .region(region)
                .build();
    }

    /**
     * Create a provider with default credentials and default user ID.
     *
     * <p>The region is resolved from the default AWS region provider chain
     * (environment, config file, etc.).
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
            throw new IllegalStateException("Could not resolve AWS region from default provider chain", e);
        }
        if (region == null) {
            throw new IllegalStateException("Could not resolve AWS region from default provider chain");
        }
        return create(cacheName, region);
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "IamAuthTokenProvider{" +
                "cacheName='" + getCacheName() + '\'' +
                ", userId='" + getUserId() + '\'' +
                ", region=" + getRegion() +
                '}';
    }

    /**
     * Builder for {@link IamAuthTokenProvider}.
     */
    public static final class Builder {
        private String cacheName;
        private String userId;
        private Region region;
        private AwsCredentialsProvider credentialsProvider;
        private Duration expiresIn;

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
         * <p>Retained for source compatibility. The ElastiCache toolkit fixes
         * token lifetime at 15 minutes, so any other value is rejected.
         *
         * @param expiresIn the expiration duration
         */
        public Builder expiresIn(Duration expiresIn) {
            this.expiresIn = expiresIn;
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

            AwsCredentialsProvider creds = credentialsProvider != null
                    ? credentialsProvider
                    : DefaultCredentialsProvider.builder().build();

            IamAuthTokenGenerator.Builder generatorBuilder = IamAuthTokenGenerator.builder()
                    .cacheName(cacheName)
                    .region(region)
                    .credentialsProvider(creds);

            if (userId != null && !userId.isEmpty()) {
                generatorBuilder.userId(userId);
            }
            if (expiresIn != null) {
                generatorBuilder.expiresIn(expiresIn);
            }

            return new IamAuthTokenProvider(generatorBuilder.build());
        }
    }
}
