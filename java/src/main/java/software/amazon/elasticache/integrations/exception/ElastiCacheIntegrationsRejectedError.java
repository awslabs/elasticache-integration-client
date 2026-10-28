package software.amazon.elasticache.integrations.exception;

/** Cache explicitly rejected a request with a RESP error reply. */
public class ElastiCacheIntegrationsRejectedError extends ElastiCacheIntegrationsError {

    /** The RESP error code from the reply. */
    private final String code;

    /**
     * Create an error for a request that Cache rejected.
     *
     * @param code the RESP error code, such as {@code ERR}
     * @param message the error message from the reply
     */
    public ElastiCacheIntegrationsRejectedError(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * Return the RESP error code.
     *
     * @return the RESP error code, such as {@code ERR}
     */
    public String getCode() {
        return code;
    }
}
