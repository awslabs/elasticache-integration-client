package software.amazon.elasticache.integrations.exception;

/** Cache explicitly rejected a request with a RESP error reply. */
public class ElastiCacheIntegrationsRejectedError extends ElastiCacheIntegrationsError {

    private final String code;

    public ElastiCacheIntegrationsRejectedError(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
