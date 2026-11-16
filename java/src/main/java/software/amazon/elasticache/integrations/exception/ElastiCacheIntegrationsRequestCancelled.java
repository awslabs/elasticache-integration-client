package software.amazon.elasticache.integrations.exception;

/** A Cache request was cancelled, for example because the SDK aborted the attempt. */
public final class ElastiCacheIntegrationsRequestCancelled extends ElastiCacheIntegrationsError {
    /** Create the cancellation signal. */
    public ElastiCacheIntegrationsRequestCancelled() {
        super("Cache request was cancelled");
    }
}
