package software.amazon.photon.session;

import java.util.function.Supplier;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenManager;
import software.amazon.elasticache.auth.ToolkitInputException;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.auth.IamAuthTokenProvider;
import software.amazon.photon.exception.PhotonError;

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
            throw new PhotonError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PhotonError("Failed to obtain refreshed IAM auth token", e);
        }
    }

    void refresh() {
        try {
            tokenManager.refreshToken();
        } catch (ToolkitInputException e) {
            throw new PhotonError(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PhotonError("Failed to force-refresh IAM auth token", e);
        }
    }

    @Override
    public void close() {
        tokenManager.close();
    }
}
