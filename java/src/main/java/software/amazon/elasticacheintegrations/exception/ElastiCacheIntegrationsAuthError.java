package software.amazon.elasticacheintegrations.exception;

/**
 * The Cache endpoint rejected credentials during {@code HELLO 3 AUTH} or a subsequent {@code AUTH},
 * or returned an invalid authentication reply.
 *
 * <p>A bring-up rejection invalidates that connection attempt. A background re-AUTH rejection
 * leaves the established connection open with its last valid authentication state.
 */
public class ElastiCacheIntegrationsAuthError extends ElastiCacheIntegrationsConnectionError {

    public ElastiCacheIntegrationsAuthError(String message) {
        super(message);
    }
}
