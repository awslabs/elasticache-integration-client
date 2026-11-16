package software.amazon.elasticache.integrations.session;

import java.io.IOException;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOriginError;
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
        if (failure instanceof CacheUnavailableFailure) {
            return true;
        }
        if (!session.isOpen()) {
            return false; // session closed: never retry
        }
        // Checked before idempotency: without the cache, the SDK retries any operation after its
        // connection drops, so a lost cache connection is retryable for every operation too.
        if (failure instanceof ElastiCacheIntegrationsConnectionLost
                && !session.isConnectionHealthy()) {
            return true; // retryable error so the SDK decides whether to retry or not
        }
        if (!Idempotency.isIdempotent(request.getMetadata())) {
            return false; // non-idempotent operations stop here for other failures
        }
        if (!session.isConnectionHealthy()) {
            return isTransportFailure(failure);
        }
        if (failure instanceof ElastiCacheIntegrationsRejectedError) {
            return ORIGIN_ERROR.equals(((ElastiCacheIntegrationsRejectedError) failure).getCode());
        }
        return failure instanceof ElastiCacheIntegrationsTimeoutError;
    }

    /**
     * The named SDK error for a failure {@link #shouldRetry} accepted, or {@code null} if it has
     * none.
     */
    static IOException namedFailure(ElastiCacheIntegrationsError failure) {
        if (failure instanceof CacheUnavailableFailure) {
            return new ElastiCacheIntegrationsCacheUnavailable(failure.getMessage(), failure);
        }
        if (failure instanceof ElastiCacheIntegrationsRejectedError
                && ORIGIN_ERROR.equals(((ElastiCacheIntegrationsRejectedError) failure).getCode())) {
            return new ElastiCacheIntegrationsOriginError(
                    "Cache could not complete the origin request", failure);
        }
        return null;
    }

    private static boolean isTransportFailure(ElastiCacheIntegrationsError failure) {
        return failure instanceof ElastiCacheIntegrationsTimeoutError
                || failure instanceof ElastiCacheIntegrationsConnectionError;
    }
}
