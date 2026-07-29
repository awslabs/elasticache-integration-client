package software.amazon.photon.auth;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.photon.exception.PhotonError;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Generates IAM authentication tokens for ElastiCache Serverless caches.
 *
 * <p>The token is a SigV4-presigned URL (with scheme stripped) that the cache
 * validates as a password. This matches the token format used by ElastiCache
 * IAM authentication.
 *
 * <p>Example token format:
 * <pre>
 * my-cache/?Action=connect&User=default&ResourceType=ServerlessCache
 * &X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=...&X-Amz-Date=...
 * &X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=...
 * </pre>
 *
 * <p>Usage:
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

    /** SigV4 presign expiry: maximum accepted by ElastiCache (15 minutes). */
    public static final Duration TOKEN_EXPIRY = Duration.ofSeconds(900);

    /** Default ElastiCache IAM user for serverless caches. */
    public static final String DEFAULT_USER_ID = "default";

    private static final String SERVICE_NAME = "elasticache";
    private static final String HTTP_SCHEME = "http://";

    private final String cacheName;
    private final String userId;
    private final Region region;
    private final AwsCredentialsProvider credentialsProvider;
    private final Duration expiresIn;
    private final Clock clock;

    private IamAuthTokenGenerator(Builder builder) {
        this.cacheName = Objects.requireNonNull(builder.cacheName, "cacheName is required");
        this.userId = builder.userId != null ? builder.userId : DEFAULT_USER_ID;
        this.region = Objects.requireNonNull(builder.region, "region is required");
        this.credentialsProvider = Objects.requireNonNull(builder.credentialsProvider,
                "credentialsProvider is required");
        this.expiresIn = builder.expiresIn != null ? builder.expiresIn : TOKEN_EXPIRY;
        this.clock = builder.clock != null ? builder.clock : Clock.systemUTC();

        if (cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName must not be empty");
        }
    }

    /**
     * Generate a fresh IAM auth token.
     *
     * <p>Credentials are resolved on every call so rotated credentials are
     * picked up automatically.
     *
     * @return the signed token string (presigned URL with scheme stripped)
     * @throws PhotonError if credential resolution or signing fails
     */
    public String generateToken() {
        AwsCredentials credentials;
        try {
            credentials = credentialsProvider.resolveCredentials();
        } catch (Exception e) {
            throw new PhotonError("No AWS credentials available for IAM auth token generation", e);
        }

        if (credentials == null) {
            throw new PhotonError("No AWS credentials available for IAM auth token generation");
        }

        try {
            // Build the presign request
            URI uri = URI.create(HTTP_SCHEME + cacheName + "/");
            SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                    .uri(uri)
                    .method(SdkHttpMethod.GET)
                    .putRawQueryParameter("Action", "connect")
                    .putRawQueryParameter("User", userId)
                    .putRawQueryParameter("ResourceType", "ServerlessCache")
                    .build();

            Instant signingTime = clock.instant();

            Aws4PresignerParams.Builder presignerParams = Aws4PresignerParams.builder()
                    .signingRegion(region)
                    .signingName(SERVICE_NAME)
                    .signingClockOverride(Clock.fixed(signingTime, clock.getZone()))
                    .expirationTime(signingTime.plus(expiresIn))
                    .awsCredentials(credentials);

            // Add session token if present
            if (credentials instanceof AwsSessionCredentials) {
                // Session token is handled automatically by the signer
            }

            Aws4Signer signer = Aws4Signer.create();
            SdkHttpFullRequest signedRequest = signer.presign(request, presignerParams.build());

            // Convert to URL string and strip the http:// scheme
            String url = signedRequest.getUri().toString();
            if (url.startsWith(HTTP_SCHEME)) {
                url = url.substring(HTTP_SCHEME.length());
            }

            return url;
        } catch (PhotonError e) {
            throw e;
        } catch (Exception e) {
            throw new PhotonError("Failed to generate IAM auth token", e);
        }
    }

    /**
     * Get the user ID that will be used in generated tokens.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Get the cache name.
     */
    public String getCacheName() {
        return cacheName;
    }

    /**
     * Get the region.
     */
    public Region getRegion() {
        return region;
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "IamAuthTokenGenerator{" +
                "cacheName='" + cacheName + '\'' +
                ", userId='" + userId + '\'' +
                ", region=" + region +
                '}';
    }

    /**
     * Builder for {@link IamAuthTokenGenerator}.
     */
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
         * Set the AWS region (required).
         *
         * @param region the AWS region
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * Set the credentials provider (required).
         *
         * @param credentialsProvider the AWS credentials provider
         */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        /**
         * Set the token expiration duration (optional, defaults to 15 minutes).
         *
         * @param expiresIn the expiration duration
         */
        public Builder expiresIn(Duration expiresIn) {
            this.expiresIn = expiresIn;
            return this;
        }

        /**
         * Set the clock for signing (optional, for testing).
         *
         * @param clock the clock to use
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
