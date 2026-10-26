package software.amazon.elasticache.integrations.session;

import java.util.function.Supplier;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/** Internal connector adapter for the toolkit's IAM token manager. */
final class ManagedIamAuthProvider implements Supplier<AuthCredentials>, AutoCloseable {
    private final ElastiCacheIamAuthTokenManager tokenManager;

    ManagedIamAuthProvider(IamAuthTokenProvider provider) {
        this.tokenManager = provider.newTokenManager();
    }

    @Override
    public AuthCredentials get() {
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials = tokenManager.getCredentials();
            return new AuthCredentials(credentials.getUserId(), credentials.getToken());
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to obtain refreshed IAM auth token", e);
        }
    }

    void refresh() {
        try {
            tokenManager.refreshToken();
        } catch (ToolkitInputException e) {
            throw new ElastiCacheIntegrationsError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ElastiCacheIntegrationsError("Failed to force-refresh IAM auth token", e);
        }
    }

    @Override
    public void close() {
        tokenManager.close();
    }
}
