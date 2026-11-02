package software.amazon.elasticache.integrations.exception;

/** The resolved config carried a predicate this client cannot evaluate. */
public class ElastiCacheIntegrationsConfigUnevaluable extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsConfigUnevaluable(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsConfigUnevaluable(String message, Throwable cause) {
        super(message, cause);
    }
}
