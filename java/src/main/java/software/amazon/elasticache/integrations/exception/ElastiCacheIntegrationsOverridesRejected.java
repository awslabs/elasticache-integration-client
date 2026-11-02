package software.amazon.elasticache.integrations.exception;

/** The proxy rejected the {@code aws.config} overrides ({@code CONFIG_INVALID}). */
public class ElastiCacheIntegrationsOverridesRejected extends ElastiCacheIntegrationsError {

    public ElastiCacheIntegrationsOverridesRejected(String message) {
        super(message);
    }
}
