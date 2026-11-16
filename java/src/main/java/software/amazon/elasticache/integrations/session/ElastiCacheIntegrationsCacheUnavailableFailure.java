package software.amazon.elasticache.integrations.session;

import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * The session has no cache connection and the fallback window has closed. The HTTP clients raise it
 * to the SDK as {@link
 * software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable}.
 */
final class ElastiCacheIntegrationsCacheUnavailableFailure extends ElastiCacheIntegrationsError {
    ElastiCacheIntegrationsCacheUnavailableFailure(String message) {
        super(message);
    }
}
