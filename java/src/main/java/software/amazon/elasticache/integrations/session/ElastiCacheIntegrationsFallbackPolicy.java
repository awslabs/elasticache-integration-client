package software.amazon.elasticache.integrations.session;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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

    /**
     * RESP error codes whose outcome is unknown, so the SDK may retry. Every other code is terminal.
     */
    private static final Set<String> RETRYABLE_CODES =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList("ORIGIN_ERROR", "INTERNAL_ERROR")));

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
        if (failure instanceof ElastiCacheIntegrationsCacheUnavailableFailure) {
            return true;
        }
        if (!session.isOpen()) {
            return false; // session closed: never retry
        }
        // As without the cache, a transport failure is retryable for every operation. A lost
        // connection on a healthy connection is an interrupted request, which is not retried.
        if (!session.isConnectionHealthy()) {
            return failure instanceof ElastiCacheIntegrationsConnectionLost
                    || isTransportFailure(failure);
        }
        if (failure instanceof ElastiCacheIntegrationsRejectedError) {
            String code = ((ElastiCacheIntegrationsRejectedError) failure).getCode();
            return RETRYABLE_CODES.contains(code);
        }
        return failure instanceof ElastiCacheIntegrationsTimeoutError;
    }

    /**
     * The named SDK error for a failure {@link #shouldRetry} accepted, or {@code null} if it has
     * none.
     */
    static IOException namedFailure(ElastiCacheIntegrationsError failure) {
        if (failure instanceof ElastiCacheIntegrationsCacheUnavailableFailure) {
            return new ElastiCacheIntegrationsCacheUnavailable(failure.getMessage());
        }
        if (failure instanceof ElastiCacheIntegrationsRejectedError
                && RETRYABLE_CODES.contains(((ElastiCacheIntegrationsRejectedError) failure).getCode())) {
            return new ElastiCacheIntegrationsOriginError(
                    "ElastiCacheIntegrations could not complete the origin request", failure);
        }
        return null;
    }

    private static boolean isTransportFailure(ElastiCacheIntegrationsError failure) {
        return failure instanceof ElastiCacheIntegrationsTimeoutError
                || failure instanceof ElastiCacheIntegrationsConnectionError;
    }
}
