package software.amazon.elasticache.integrations.exception;

/** A RESP frame was malformed or could not be decoded. */
public class ElastiCacheIntegrationsProtocolError extends ElastiCacheIntegrationsError {

    /**
     * Create a protocol error.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsProtocolError(String message) {
        super(message);
    }

    /**
     * Create a protocol error with a cause.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsProtocolError(String message, Throwable cause) {
        super(message, cause);
    }
}
