package software.amazon.elasticache.integrations.exception;

/** The resolved config carried a predicate this client cannot evaluate. */
public class ElastiCacheIntegrationsConfigUnevaluable extends ElastiCacheIntegrationsError {

    /**
     * Create an error for a config predicate that cannot be evaluated.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsConfigUnevaluable(String message) {
        super(message);
    }

    /**
     * Create an error, with a cause, for a config predicate that cannot be evaluated.
     *
     * @param message the detail message
     * @param cause the underlying failure
     */
    public ElastiCacheIntegrationsConfigUnevaluable(String message, Throwable cause) {
        super(message, cause);
    }
}
