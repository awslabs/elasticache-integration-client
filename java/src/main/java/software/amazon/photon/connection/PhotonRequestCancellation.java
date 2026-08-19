package software.amazon.photon.connection;

import software.amazon.photon.exception.PhotonError;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internal cancellation signal for one in-flight Photon request.
 *
 * <p>Cancellation completes the caller's future but does not remove its FIFO
 * slot. The reader must still consume and discard the corresponding response
 * before resolving later requests.
 */
public final class PhotonRequestCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Runnable> action = new AtomicReference<>();

    /**
     * Cancel the request. Idempotent.
     */
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
     */
    public boolean isCancelled() {
        return cancelled.get();
    }

    void register(Runnable cancellationAction) {
        if (!action.compareAndSet(null, cancellationAction)) {
            throw new IllegalStateException("Cancellation action already registered");
        }
        if (cancelled.get() && action.compareAndSet(cancellationAction, null)) {
            cancellationAction.run();
        }
    }

    void clear(Runnable cancellationAction) {
        action.compareAndSet(cancellationAction, null);
    }

    /**
     * Signals that an individual Photon request was canceled.
     */
    public static final class CancelledException extends PhotonError {
        public CancelledException() {
            super("Photon request was cancelled");
        }
    }
}
