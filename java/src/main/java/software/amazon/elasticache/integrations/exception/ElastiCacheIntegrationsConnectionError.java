package software.amazon.elasticache.integrations.exception;

/** Connection bring-up failed. */
public class ElastiCacheIntegrationsConnectionError extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsConnectionError(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsConnectionError(String message, Throwable cause) {
        super(message, cause);
    }
}
