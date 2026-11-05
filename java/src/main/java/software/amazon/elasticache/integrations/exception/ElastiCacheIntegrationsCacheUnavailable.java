package software.amazon.elasticache.integrations.exception;

/** Cache cannot safely serve a routed request or send it directly to origin. */
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
