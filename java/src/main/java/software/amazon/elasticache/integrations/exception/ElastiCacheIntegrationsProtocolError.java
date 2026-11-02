package software.amazon.elasticache.integrations.exception;

/** Malformed RESP frame - garbage data received. */
public class ElastiCacheIntegrationsProtocolError extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsProtocolError(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsProtocolError(String message, Throwable cause) {
        super(message, cause);
    }
}
