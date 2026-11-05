package software.amazon.elasticache.integrations.connection;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.resp.RespValue;

/**
 * Thread-safe home for FIFO and request-ID-correlated pending futures.
 *
 * <p>Ordinary replies are resolved in FIFO order. Deferred replies move their future into an ID map
 * until a matching push arrives:
 *
 * <ol>
 *   <li>Callers register a future before sending (via {@link #registerNewRequest()})
 *   <li>The reader thread resolves futures in order (via {@link #resolveOldestRequest(RespValue)})
 *   <li>Deferred requests are promoted and later resolved by request ID
 * </ol>
 *
 * <p>All operations are thread-safe. Requests awaiting either a positional FIFO reply or a deferred
 * by-ID reply together count against a configurable maximum, so moving a request to ID-based
 * tracking does not free additional capacity.
 */
public class PendingRequests {

    /** Default maximum requests awaiting either a FIFO or a deferred reply. */
    public static final int DEFAULT_MAX_PENDING = 100;

    enum TimeoutDisposition {
        /** An async request was removed; the connection can remain usable. */
        ASYNC_REMOVED,
        /** The request completed concurrently with timeout handling. */
        COMPLETED,
        /** A FIFO response timeout invalidates correlation, so all pending requests must fail. */
        FATAL
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacityAvailable = lock.newCondition();
    private final Queue<PendingRequest> queue = new ArrayDeque<>();
    private final Map<String, PendingRequest> asyncResponses = new HashMap<>();
    private final int maxPending;
    private volatile boolean closed = false;

    private static final class PendingRequest {
        private final CompletableFuture<RespValue> future;
        private final boolean deferredResponseAllowed;

        private PendingRequest(CompletableFuture<RespValue> future, boolean deferredResponseAllowed) {
            this.future = future;
            this.deferredResponseAllowed = deferredResponseAllowed;
        }
    }

    /** Create a pending requests queue with default max size. */
    public PendingRequests() {
        this(DEFAULT_MAX_PENDING);
    }

    /**
     * Create a pending requests queue with a custom max size.
     *
     * @param maxPending maximum number of requests awaiting either a FIFO or a deferred reply (must
     *     be positive)
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
     * <p>The caller must send the request immediately after registering to maintain FIFO correlation.
     * This should be called while holding the send lock.
     *
     * @return a future that will be completed when the response arrives
     * @throws ElastiCacheIntegrationsConnectionLost if the queue is closed
     * @throws ElastiCacheIntegrationsError if capacity is exhausted
     */
    public CompletableFuture<RespValue> registerNewRequest() {
        return registerNewRequest(true);
    }

    /**
     * Register a new pending request with an explicit deferred-response policy.
     *
     * <p>Only Cache request calls accept a request-ID reply. Raw commands remain FIFO-correlated and
     * return arbitrary RESP simple strings directly.
     */
    CompletableFuture<RespValue> registerNewRequest(boolean deferredResponseAllowed) {
        lock.lock();
        try {
            ensureOpen();
            if (queue.size() + asyncResponses.size() >= maxPending) {
                // Capacity exhaustion does not mean the connection was lost.
                throw new ElastiCacheIntegrationsError(
                        "Too many pending requests (max=" + maxPending + ")");
            }
            return addRequest(deferredResponseAllowed);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Wait up to the supplied budget for pending-request capacity, then register.
     *
     * <p>This wait happens before the request is written, so timing out here does not invalidate FIFO
     * correlation or the connection.
     */
    CompletableFuture<RespValue> registerNewRequest(
            boolean deferredResponseAllowed, long timeout, TimeUnit unit) throws InterruptedException {
        long remainingNanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            ensureOpen();
            while (queue.size() + asyncResponses.size() >= maxPending) {
                if (remainingNanos <= 0) {
                    throw new ElastiCacheIntegrationsTimeoutError(
                            "Timed out waiting for connection capacity");
                }
                remainingNanos = capacityAvailable.awaitNanos(remainingNanos);
                ensureOpen();
            }
            return addRequest(deferredResponseAllowed);
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
    public boolean resolveOldestRequest(RespValue value) {
        lock.lock();
        try {
            PendingRequest pending = queue.poll();
            if (pending != null) {
                pending.future.complete(value);
                capacityAvailable.signal();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Move the oldest FIFO request into request-ID-based tracking.
     *
     * @param requestId deferred response request ID
     * @return true when the FIFO request was consumed as a deferred response, false when the request
     *     expects an ordinary FIFO response
     * @throws ElastiCacheIntegrationsProtocolError if no FIFO request exists or the ID is already
     *     active
     */
    public boolean moveNextToAsyncResponses(String requestId) {
        lock.lock();
        try {
            if (queue.isEmpty()) {
                throw new ElastiCacheIntegrationsProtocolError(
                        "Received async response ID without a pending FIFO request: " + requestId);
            }
            PendingRequest pending = queue.peek();
            if (!pending.deferredResponseAllowed) {
                return false;
            }
            if (asyncResponses.containsKey(requestId)) {
                throw new ElastiCacheIntegrationsProtocolError("Duplicate async response ID: " + requestId);
            }
            if (pending.future.isDone()) {
                queue.remove();
                capacityAvailable.signal();
                return true;
            }
            asyncResponses.put(requestId, queue.remove());
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolve and remove a request tracked by ID.
     *
     * @param requestId the request ID from the {@code aws.response} push
     * @param value the response value
     * @return true when the ID was active, false for an unknown or timed-out ID
     */
    public boolean resolveAsync(String requestId, RespValue value) {
        lock.lock();
        try {
            PendingRequest pending = asyncResponses.remove(requestId);
            if (pending == null) {
                return false;
            }
            pending.future.complete(value);
            capacityAvailable.signal();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove a deferred request by future identity.
     *
     * @param future the exact future returned at registration
     * @return true when the future was asynchronously tracked and removed
     */
    public boolean removeFromAsync(CompletableFuture<RespValue> future) {
        lock.lock();
        try {
            return removeFromAsyncLocked(future);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handle an already-detected response timeout and update pending state atomically.
     *
     * <p>Async requests are removed without closing the tracker. FIFO timeouts fail all pending
     * requests and are fatal. A request completed concurrently is reported as {@link
     * TimeoutDisposition#COMPLETED}.
     */
    TimeoutDisposition onTimeout(CompletableFuture<RespValue> future, Throwable cause) {
        lock.lock();
        try {
            if (removeFromAsyncLocked(future)) {
                return TimeoutDisposition.ASYNC_REMOVED;
            }
            if (future.isDone()) {
                return TimeoutDisposition.COMPLETED;
            }
            failAllLocked(cause);
            return TimeoutDisposition.FATAL;
        } finally {
            lock.unlock();
        }
    }

    /** Cancel one request while retaining its FIFO slot until its reply is consumed. */
    void cancel(CompletableFuture<RespValue> future, Throwable cause) {
        lock.lock();
        try {
            removeFromAsyncLocked(future);
            future.completeExceptionally(cause);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Fail all pending requests with the given exception and mark closed.
     *
     * <p>Called when the connection dies (timeout, EOF, error). After this, no new requests can be
     * registered.
     *
     * @param cause the exception to fail pending requests with
     */
    public void failAll(Throwable cause) {
        lock.lock();
        try {
            failAllLocked(cause);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove the async mapping for the exact future, if present.
     *
     * <p>The caller must hold {@code lock}.
     */
    @SuppressWarnings("ReferenceEquality") // Matches the exact future, not an equal one.
    private boolean removeFromAsyncLocked(CompletableFuture<RespValue> future) {
        Iterator<Map.Entry<String, PendingRequest>> iterator = asyncResponses.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().future == future) {
                iterator.remove();
                capacityAvailable.signal();
                return true;
            }
        }
        return false;
    }

    /**
     * Fail all pending FIFO and async requests, and prevent new registrations
     *
     * <p>The caller must hold {@code lock}.
     */
    private void failAllLocked(Throwable cause) {
        closed = true;
        PendingRequest pending;
        while ((pending = queue.poll()) != null) {
            pending.future.completeExceptionally(cause);
        }
        for (PendingRequest asyncPending : asyncResponses.values()) {
            asyncPending.future.completeExceptionally(cause);
        }
        asyncResponses.clear();
        capacityAvailable.signalAll();
    }

    private void ensureOpen() {
        if (closed) {
            throw new ElastiCacheIntegrationsConnectionLost("Connection is closed");
        }
    }

    private CompletableFuture<RespValue> addRequest(boolean deferredResponseAllowed) {
        CompletableFuture<RespValue> future = new CompletableFuture<>();
        queue.add(new PendingRequest(future, deferredResponseAllowed));
        return future;
    }

    /**
     * Get the current number of requests awaiting FIFO replies.
     *
     * @return the number of requests awaiting FIFO replies
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
     *
     * @return true after the queue has been closed
     */
    public boolean isClosed() {
        return closed;
    }
}
