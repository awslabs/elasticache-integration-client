package software.amazon.elasticache.integrations.exception;

import java.io.IOException;

/**
 * Cache could not complete the request to the origin service.
 *
 * <p>A call without the cache can fail the same way, so this is an {@link IOException}, not an
 * {@link ElastiCacheIntegrationsError}, and the SDK retries it as your retry settings allow.
 */
public final class ElastiCacheIntegrationsOriginError extends IOException {
    /**
     * Create an error for an origin request the cache could not complete.
     *
     * @param message the detail message
     * @param cause the cache's error reply
     */
    public ElastiCacheIntegrationsOriginError(String message, Throwable cause) {
        super(message, cause);
    }
}
