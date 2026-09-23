package software.amazon.photon.auth;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.photon.exception.PhotonError;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Supplies cached IAM credentials and refreshes the token before it expires.
 *
 * <p>The first call to {@link #get()} generates a token lazily. Later calls
 * reuse the current valid token while the ElastiCache toolkit refreshes it in
 * the background. Each refresh resolves AWS credentials again, so credential
 * rotation is picked up without waiting for a Photon reconnect.
 *
 * <p>Close the manager when the Photon client or handle is closed.
 */
public final class IamAuthTokenManager
        implements Supplier<AuthCredentials>, AutoCloseable {

    private final String cacheName;
    private final Region region;
    private final ElastiCacheIamAuthTokenManager toolkitTokenManager;

    private IamAuthTokenManager(Builder builder) {
        String requestedCacheName =
                Objects.requireNonNull(builder.cacheName, "cacheName is required");
        String requestedUserId = builder.userId != null && !builder.userId.isEmpty()
                ? builder.userId : IamAuthTokenGenerator.DEFAULT_USER_ID;
        this.region = Objects.requireNonNull(builder.region, "region is required");
        AwsCredentialsProvider credentialsProvider = builder.credentialsProvider != null
                ? builder.credentialsProvider
                : DefaultCredentialsProvider.builder().build();

        try {
            ElastiCacheIamAuthTokenManager.Builder managerBuilder =
                    ElastiCacheIamAuthTokenManager.builder()
                            .serverlessCacheName(requestedCacheName)
                            .userId(requestedUserId)
                            .region(region)
                            .credentialsProvider(credentialsProvider);
            if (builder.refreshAfter != null) {
                managerBuilder.refreshAfter(builder.refreshAfter);
            }
            this.toolkitTokenManager = managerBuilder.build();
        } catch (ToolkitInputException e) {
            throw new PhotonError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PhotonError("Failed to initialize IAM token auto-refresh", e);
        }
        this.cacheName = requestedCacheName.toLowerCase(Locale.ROOT);
    }

    /**
     * Return the normalized user ID and the current valid token.
     */
    @Override
    public AuthCredentials get() {
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials =
                    toolkitTokenManager.getCredentials();
            return new AuthCredentials(credentials.getUserId(), credentials.getToken());
        } catch (ToolkitInputException e) {
            throw new PhotonError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PhotonError("Failed to obtain refreshed IAM auth token", e);
        }
    }

    /**
     * Get the normalized user ID used in generated credentials.
     */
    public String getUserId() {
        return toolkitTokenManager.getUserId();
    }

    /**
     * Get the normalized cache name.
     */
    public String getCacheName() {
        return cacheName;
    }

    /**
     * Get the signing region.
     */
    public Region getRegion() {
        return region;
    }

    /**
     * Stop background refresh and release the toolkit scheduler.
     */
    @Override
    public void close() {
        toolkitTokenManager.close();
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "IamAuthTokenManager{" +
                "cacheName='" + cacheName + '\'' +
                ", userId='" + getUserId() + '\'' +
                ", region=" + region +
                '}';
    }

    /**
     * Builder for {@link IamAuthTokenManager}.
     */
    public static final class Builder {
        private String cacheName;
        private String userId;
        private Region region;
        private AwsCredentialsProvider credentialsProvider;
        private Duration refreshAfter;

        private Builder() {}

        /**
         * Set the ElastiCache serverless cache name.
         */
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }

        /**
         * Set the IAM-enabled ElastiCache user ID.
         */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Set the AWS signing region.
         */
        public Builder region(Region region) {
            this.region = region;
            return this;
        }

        /**
         * Set the AWS credentials provider.
         */
        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        /**
         * Set how long after issuance the token refreshes in the background.
         *
         * <p>The toolkit default is five minutes. Values must be at least one
         * millisecond and less than 14 minutes 40 seconds.
         */
        public Builder refreshAfter(Duration refreshAfter) {
            this.refreshAfter = refreshAfter;
            return this;
        }

        /**
         * Build the auto-refreshing credential manager.
         */
        public IamAuthTokenManager build() {
            return new IamAuthTokenManager(this);
        }
    }
}
