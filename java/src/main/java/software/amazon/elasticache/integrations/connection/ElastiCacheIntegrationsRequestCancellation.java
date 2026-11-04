package software.amazon.elasticache.integrations.connection;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Internal cancellation signal for one in-flight Cache request.
 *
 * <p>Cancellation completes the caller's future but does not remove its FIFO slot. The reader must
 * still consume and discard the corresponding response before resolving later requests.
 */
public final class ElastiCacheIntegrationsRequestCancellation {

    /** Create a request cancellation that has not been cancelled. */
    public ElastiCacheIntegrationsRequestCancellation() {}

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> action = new AtomicReference<>();

    /** Cancel the request. Idempotent. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable registered = action.getAndSet(null);
        if (registered != null) {
            registered.run();
        }
    }

    /**
     * Return whether cancellation has been requested.
     *
     * @return true once {@link #cancel()} has been called
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Run {@code cancellationAction} on cancellation, or now if the request is already cancelled.
     * Only one action may be registered at a time.
     */
    public void register(Runnable cancellationAction) {
        if (!action.compareAndSet(null, cancellationAction)) {
            throw new IllegalStateException("Cancellation action already registered");
        }
        if (cancelled.get() && action.compareAndSet(cancellationAction, null)) {
            cancellationAction.run();
        }
    }

    /** Remove {@code cancellationAction} if it is still registered. */
    public void clear(Runnable cancellationAction) {
        action.compareAndSet(cancellationAction, null);
    }

    /** Signals that an individual Cache request was canceled. */
    public static final class CancelledException extends ElastiCacheIntegrationsError {
        /** Create the cancellation signal. */
        public CancelledException() {
            super("Cache request was cancelled");
        }
    }
}
