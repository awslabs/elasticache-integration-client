package software.amazon.elasticache.integrations.session;

import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/** Internal connector adapter for the toolkit's IAM token manager. */
final class ManagedIamAuthProvider implements Supplier<AuthCredentials>, AutoCloseable {
    private final IamAuthTokenProvider provider;
    private ElastiCacheIamAuthTokenManager tokenManager;

    ManagedIamAuthProvider(IamAuthTokenProvider provider) {
        this(provider, false);
    }

    ManagedIamAuthProvider(IamAuthTokenProvider provider, boolean deferInitialization) {
        this.provider = provider;
        if (!deferInitialization) {
            this.tokenManager = provider.newTokenManager();
        }
    }

    synchronized void bindTo(SdkServiceClientConfiguration clientConfiguration) {
        if (tokenManager != null) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is already initialized");
        }
        if (!(clientConfiguration instanceof AwsServiceClientConfiguration)) {
            throw new ElastiCacheIntegrationsError(
                    "AWS SDK client configuration is required for IAM authentication");
        }

        AwsServiceClientConfiguration awsConfiguration =
                (AwsServiceClientConfiguration) clientConfiguration;
        if (!provider.getRegion().equals(awsConfiguration.region())) {
            throw new ElastiCacheIntegrationsError(
                    "Cache IAM region "
                            + provider.getRegion()
                            + " does not match AWS SDK client region "
                            + awsConfiguration.region());
        }
        tokenManager =
                provider.newTokenManager(asCredentialsProvider(awsConfiguration.credentialsProvider()));
    }

    @Override
    public synchronized AuthCredentials get() {
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials = tokenManager().getCredentials();
            return new AuthCredentials(credentials.getUserId(), credentials.getToken());
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to obtain refreshed IAM auth token", e);
        }
    }

    synchronized void refresh() {
        try {
            tokenManager().refreshToken();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to force-refresh IAM auth token", e);
        }
    }

    @Override
    public synchronized void close() {
        if (tokenManager != null) {
            tokenManager.close();
            tokenManager = null;
        }
    }

    private ElastiCacheIamAuthTokenManager tokenManager() {
        if (tokenManager == null) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is not initialized");
        }
        return tokenManager;
    }

    private static AwsCredentialsProvider asCredentialsProvider(
            IdentityProvider<? extends AwsCredentialsIdentity> identityProvider) {
        if (identityProvider instanceof AwsCredentialsProvider) {
            return (AwsCredentialsProvider) identityProvider;
        }
        return () -> resolveCredentials(identityProvider);
    }

    private static AwsCredentials resolveCredentials(
            IdentityProvider<? extends AwsCredentialsIdentity> identityProvider) {
        AwsCredentialsIdentity identity;
        try {
            identity = identityProvider.resolveIdentity().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw e;
        }
        if (identity instanceof AwsSessionCredentialsIdentity) {
            return AwsSessionCredentials.create(
                    identity.accessKeyId(),
                    identity.secretAccessKey(),
                    ((AwsSessionCredentialsIdentity) identity).sessionToken());
        }
        return AwsBasicCredentials.create(identity.accessKeyId(), identity.secretAccessKey());
    }
}
