// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import java.util.function.Supplier;
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
    private ElastiCacheIamAuthTokenManager tokenManager;
    private boolean closed;

    /** Create a provider with no token manager yet. Call {@link #bind} before first use. */
    ManagedIamAuthProvider() {}

    ManagedIamAuthProvider(IamAuthTokenProvider provider) {
        bind(provider);
    }

    /** Create the token manager from {@code provider}. */
    synchronized void bind(IamAuthTokenProvider provider) {
        if (closed) {
            throw new ElastiCacheIntegrationsError("IAM auth provider is closed");
        }
        if (tokenManager != null) {
            throw new IllegalStateException("IAM token manager is already bound");
        }
        tokenManager = provider.newTokenManager();
    }

    private synchronized ElastiCacheIamAuthTokenManager manager() {
        if (tokenManager == null) {
            throw new ElastiCacheIntegrationsError("IAM token manager is not ready");
        }
        return tokenManager;
    }

    @Override
    public AuthCredentials get() {
        try {
            ElastiCacheIamAuthTokenManager.Credentials credentials = manager().getCredentials();
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
            manager().refreshToken();
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
        ElastiCacheIamAuthTokenManager toClose;
        synchronized (this) {
            closed = true;
            toClose = tokenManager;
        }
        if (toClose != null) {
            toClose.close();
        }
    }
}
