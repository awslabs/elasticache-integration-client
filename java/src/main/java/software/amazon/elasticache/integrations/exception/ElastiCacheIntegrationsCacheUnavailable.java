package software.amazon.elasticache.integrations.exception;

import java.io.IOException;

/**
 * Cache has no connection to the cache and the fallback window has closed.
 *
 * <p>This is an {@link IOException}, not an {@link ElastiCacheIntegrationsError}, so the SDK
 * retries it as your retry settings allow.
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

    /**
     * Create an error for a cache with no usable connection.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsCacheUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
