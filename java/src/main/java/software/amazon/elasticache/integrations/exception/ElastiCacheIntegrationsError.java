package software.amazon.elasticache.integrations.exception;

/** Base exception for all Cache errors. */
public class ElastiCacheIntegrationsError extends RuntimeException {

    public ElastiCacheIntegrationsError(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsError(String message, Throwable cause) {
        super(message, cause);
    }
}
