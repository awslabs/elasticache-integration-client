package software.amazon.elasticache.integrations.exception;

import java.io.IOException;

/**
 * ElastiCacheIntegrations could not serve a request from the cache and origin fallback is not
 * allowed.
 *
 * <p>This is an {@link IOException}, not an {@link ElastiCacheIntegrationsError}, so the SDK
 * retries it as your retry settings allow. If every retry fails, it reaches you as the cause of the
 * SDK's exception.
 */
public final class ElastiCacheIntegrationsCacheUnavailable extends IOException {
    /**
     * Create an error for a cache with no usable connection.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsCacheUnavailable(String message) {
        super(message);
    }
}
