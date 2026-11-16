package software.amazon.elasticache.integrations.exception;

/** The cache rejected the {@code aws.config} overrides ({@code CONFIG_INVALID}). */
public class ElastiCacheIntegrationsOverridesRejected extends ElastiCacheIntegrationsError {

    /**
     * Create an error for rejected config overrides.
     *
     * @param message the detail message
     */
    public ElastiCacheIntegrationsOverridesRejected(String message) {
        super(message);
    }
}
