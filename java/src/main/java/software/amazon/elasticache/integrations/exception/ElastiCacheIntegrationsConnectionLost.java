package software.amazon.elasticache.integrations.exception;

/** The Cache connection was lost while requests were in flight. */
public class ElastiCacheIntegrationsConnectionLost extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsConnectionLost(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsConnectionLost(String message, Throwable cause) {
        super(message, cause);
    }
}
