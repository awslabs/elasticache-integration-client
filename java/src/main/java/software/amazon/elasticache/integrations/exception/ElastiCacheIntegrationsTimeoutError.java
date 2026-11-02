package software.amazon.elasticache.integrations.exception;

/** Per-request timeout. */
public class ElastiCacheIntegrationsTimeoutError extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsTimeoutError(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsTimeoutError(String message, Throwable cause) {
        super(message, cause);
    }
}
