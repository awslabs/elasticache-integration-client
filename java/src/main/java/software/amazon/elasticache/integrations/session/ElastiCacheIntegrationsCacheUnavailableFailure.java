package software.amazon.elasticache.integrations.session;

import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * The session could not serve a request from the cache and origin fallback is not allowed. The HTTP
 * clients raise it to the SDK as {@link
 * software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable}.
 */
final class ElastiCacheIntegrationsCacheUnavailableFailure extends ElastiCacheIntegrationsError {
    ElastiCacheIntegrationsCacheUnavailableFailure(String message) {
        super(message);
    }
}
