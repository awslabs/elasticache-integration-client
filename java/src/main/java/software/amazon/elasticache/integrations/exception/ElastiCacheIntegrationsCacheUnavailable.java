package software.amazon.elasticache.integrations.exception;

/** Cache has no connection to the cache and the fallback window has closed. */
public final class ElastiCacheIntegrationsCacheUnavailable
        extends ElastiCacheIntegrationsConnectionError {
    /**
     * Create an error for a cache with no usable connection.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsCacheUnavailable(String message) {
        super(message);
    }
}
