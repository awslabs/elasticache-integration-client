package software.amazon.elasticache.integrations.exception;

/**
 * An option or the application's SDK client cannot be used to install ElastiCache Integrations, for
 * example a non-positive timeout, a client with no region, or a client builder that already has an
 * httpClient.
 */
public class ElastiCacheIntegrationsConfigError extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsConfigError(String message) {
        super(message);
    }

    public ElastiCacheIntegrationsConfigError(String message, Throwable cause) {
        super(message, cause);
    }
}
