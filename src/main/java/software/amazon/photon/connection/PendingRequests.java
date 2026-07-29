package software.amazon.photon.connection;

import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.resp.RespValue;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe FIFO queue of pending request futures.
 *
 * <p>The Photon protocol requires strict FIFO ordering: responses arrive in the
 * same order requests were sent. This class maintains that correlation by:
 * <ol>
 *   <li>Callers register a future before sending (via {@link #register()})</li>
 *   <li>The reader thread resolves futures in order (via {@link #resolveNext(RespValue)})</li>
 * </ol>
 *
 * <p>All operations are thread-safe. The queue has a configurable maximum size
 * to prevent unbounded growth when the connection is stalled.
 */
public class PendingRequests {

    /** Default maximum pending requests. */
    public static final int DEFAULT_MAX_PENDING = 1000;

    private final Lock lock = new ReentrantLock();
    private final Queue<CompletableFuture<RespValue>> queue = new LinkedList<>();
    private final int maxPending;
    private volatile boolean closed = false;

    /**
     * Create a pending requests queue with default max size.
     */
    public PendingRequests() {
        this(DEFAULT_MAX_PENDING);
    }

    /**
     * Create a pending requests queue with a custom max size.
     *
     * @param maxPending maximum number of pending requests (must be positive)
     */
    public PendingRequests(int maxPending) {
        if (maxPending <= 0) {
            throw new IllegalArgumentException("maxPending must be positive: " + maxPending);
        }
        this.maxPending = maxPending;
    }

    /**
     * Register a new pending request and return its future.
     *
     * <p>The caller must send the request immediately after registering to
     * maintain FIFO correlation. This should be called while holding the
     * send lock.
     *
     * @return a future that will be completed when the response arrives
     * @throws ConnectionLost if the queue is closed or at capacity
     */
    public CompletableFuture<RespValue> register() {
        lock.lock();
        try {
            if (closed) {
                throw new ConnectionLost("Connection is closed");
            }
            if (queue.size() >= maxPending) {
                throw new ConnectionLost("Too many pending requests (max=" + maxPending + ")");
            }
            CompletableFuture<RespValue> future = new CompletableFuture<>();
            queue.add(future);
            return future;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolve the oldest pending request with a successful response.
     *
     * <p>Called by the reader thread when a complete frame is received.
     *
     * @param value the RESP value to complete with
     * @return true if a pending request was resolved, false if queue was empty
     */
    public boolean resolveNext(RespValue value) {
        lock.lock();
        try {
            CompletableFuture<RespValue> future = queue.poll();
            if (future != null) {
                future.complete(value);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fail all pending requests with the given exception and mark closed.
     *
     * <p>Called when the connection dies (timeout, EOF, error). After this,
     * no new requests can be registered.
     *
     * @param cause the exception to fail pending requests with
     */
    public void failAll(Throwable cause) {
        lock.lock();
        try {
            closed = true;
            CompletableFuture<RespValue> future;
            while ((future = queue.poll()) != null) {
                future.completeExceptionally(cause);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current number of pending requests.
     */
    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if the queue is closed.
     */
    public boolean isClosed() {
        return closed;
    }
}
