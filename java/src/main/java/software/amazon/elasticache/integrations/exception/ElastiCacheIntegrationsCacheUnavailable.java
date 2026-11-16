package software.amazon.elasticache.integrations.exception;

import java.io.IOException;

/**
 * ElastiCacheIntegrations has no connection to the cache and the fallback window has closed.
 *
 * <p>This is an {@link IOException}, not an {@link ElastiCacheIntegrationsError}, so the SDK
 * retries it as your retry settings allow. If the cache is still unreachable after every retry, it
 * reaches you as the cause of the SDK's exception.
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
