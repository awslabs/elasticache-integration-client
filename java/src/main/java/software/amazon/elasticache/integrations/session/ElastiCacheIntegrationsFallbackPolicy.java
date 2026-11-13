package software.amazon.elasticache.integrations.session;

import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRequestNotSent;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;

/** Shared sync/async policy for handling a failed Cache request. */
final class ElastiCacheIntegrationsFallbackPolicy {

    private static final String ORIGIN_ERROR = "ORIGIN_ERROR";

    private ElastiCacheIntegrationsFallbackPolicy() {}

    static boolean shouldFallback(
            ElastiCacheIntegrationsSession session,
            ElastiCacheIntegrationsRequest request,
            ElastiCacheIntegrationsError failure) {
        return session.isOpen() && failure instanceof ElastiCacheIntegrationsRequestNotSent;
    }

    static boolean shouldRetry(
            ElastiCacheIntegrationsSession session,
            ElastiCacheIntegrationsRequest request,
            ElastiCacheIntegrationsError failure) {
        if (failure instanceof ElastiCacheIntegrationsCacheUnavailable) {
            return session.isOpen();
        }
        if (!session.isOpen() || !Idempotency.isIdempotent(request.getMetadata())) {
            return false;
        }
        if (!session.isConnectionHealthy()) {
            return isTransportFailure(failure);
        }
        if (failure instanceof ElastiCacheIntegrationsRejectedError) {
            return ORIGIN_ERROR.equals(((ElastiCacheIntegrationsRejectedError) failure).getCode());
        }
        return failure instanceof ElastiCacheIntegrationsTimeoutError;
    }

    private static boolean isTransportFailure(ElastiCacheIntegrationsError failure) {
        return failure instanceof ElastiCacheIntegrationsConnectionLost
                || failure instanceof ElastiCacheIntegrationsTimeoutError
                || failure instanceof ElastiCacheIntegrationsConnectionError;
    }
}
