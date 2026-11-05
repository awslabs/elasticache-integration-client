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

/**
 * Internal connector adapter for the toolkit's IAM token manager.
 *
 * <p>The installer needs the built SDK client's region and credentials to create the token manager,
 * and it builds the client after the session that uses this provider. The manager is therefore
 * attached with {@link #bind(IamAuthTokenProvider)} after construction.
 */
final class ManagedIamAuthProvider implements Supplier<AuthCredentials>, AutoCloseable {
    private final IamAuthTokenProvider provider;
    private volatile ElastiCacheIamAuthTokenManager tokenManager;
    private volatile boolean closed;

    /** Create a provider with no token manager yet. Call {@link #bind} before first use. */
    ManagedIamAuthProvider() {
        this.provider = null;
    }

    ManagedIamAuthProvider(IamAuthTokenProvider provider) {
        this(provider, false);
    }

    ManagedIamAuthProvider(IamAuthTokenProvider provider, boolean deferInitialization) {
        this.provider = provider;
        if (!deferInitialization) {
            bind(provider);
        }
    }

    synchronized void bindTo(SdkServiceClientConfiguration clientConfiguration) {
        ensureCanBind();
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

    /** Create the token manager from {@code provider}. */
    synchronized void bind(IamAuthTokenProvider provider) {
        ensureCanBind();
        tokenManager = provider.newTokenManager();
    }

    private void ensureCanBind() {
        if (closed) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is closed");
        }
        if (tokenManager != null) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is already initialized");
        }
    }

    @Override
    public AuthCredentials get() {
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials = tokenManager().getCredentials();
            return new AuthCredentials(credentials.getUserId(), credentials.getToken());
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (ElastiCacheIntegrationsError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to obtain refreshed IAM auth token", e);
        }
    }

    void refresh() {
        try {
            tokenManager().refreshToken();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (ElastiCacheIntegrationsError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to force-refresh IAM auth token", e);
        }
    }

    @Override
    public void close() {
        ElastiCacheIamAuthTokenManager manager;
        synchronized (this) {
            closed = true;
            manager = tokenManager;
            tokenManager = null;
        }
        if (manager != null) {
            manager.close();
        }
    }

    private ElastiCacheIamAuthTokenManager tokenManager() {
        ElastiCacheIamAuthTokenManager manager = tokenManager;
        if (manager == null || closed) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is not initialized");
        }
        return manager;
    }

    static AwsCredentialsProvider asCredentialsProvider(
            IdentityProvider<? extends AwsCredentialsIdentity> identityProvider) {
        if (identityProvider instanceof AwsCredentialsProvider) {
            return (AwsCredentialsProvider) identityProvider;
        }
        return () -> resolveCredentials(identityProvider);
    }

    static AwsCredentials resolveCredentials(
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
