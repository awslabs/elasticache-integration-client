// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRequestCancelled;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/** Unit tests for PendingRequests. */
class PendingRequestsTest {

    @Test
    void register_returnsFuture() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void resolveOldestRequest_completesOldestFuture() {
        PendingRequests pending = new PendingRequests();

        CompletableFuture<RespValue> f1 = pending.registerNewRequest();
        CompletableFuture<RespValue> f2 = pending.registerNewRequest();
        CompletableFuture<RespValue> f3 = pending.registerNewRequest();

        RespValue v1 = new RespSimpleString("one".getBytes(StandardCharsets.UTF_8));
        RespValue v2 = new RespSimpleString("two".getBytes(StandardCharsets.UTF_8));
        RespValue v3 = new RespSimpleString("three".getBytes(StandardCharsets.UTF_8));

        // Resolve in FIFO order
        assertTrue(pending.resolveOldestRequest(v1));
        assertTrue(f1.isDone());
        assertFalse(f2.isDone());
        assertFalse(f3.isDone());
        assertEquals(v1, f1.join());

        assertTrue(pending.resolveOldestRequest(v2));
        assertTrue(f2.isDone());
        assertFalse(f3.isDone());
        assertEquals(v2, f2.join());

        assertTrue(pending.resolveOldestRequest(v3));
        assertTrue(f3.isDone());
        assertEquals(v3, f3.join());
    }

    @Test
    void resolveOldestRequest_emptyQueue_returnsFalse() {
        PendingRequests pending = new PendingRequests();
        RespValue v = new RespSimpleString("test".getBytes(StandardCharsets.UTF_8));
        assertFalse(pending.resolveOldestRequest(v));
    }

    @Test
    void moveNextToAsyncResponses_freesFifoAndResolvesById() {
        PendingRequests pending = new PendingRequests(2);
        CompletableFuture<RespValue> deferred = pending.registerNewRequest();

        pending.moveNextToAsyncResponses("42");
        CompletableFuture<RespValue> next = pending.registerNewRequest();
        RespValue nextValue = new RespSimpleString("next".getBytes(StandardCharsets.UTF_8));
        RespValue deferredValue = new RespSimpleString("deferred".getBytes(StandardCharsets.UTF_8));

        assertEquals(1, pending.size());
        assertTrue(pending.resolveOldestRequest(nextValue));
        assertEquals(nextValue, next.join());
        assertFalse(deferred.isDone());
        assertTrue(pending.resolveAsync("42", deferredValue));
        assertEquals(deferredValue, deferred.join());
    }

    @Test
    void maxPending_countsFifoAndAsyncTogether() {
        PendingRequests pending = new PendingRequests(1);
        CompletableFuture<RespValue> deferred = pending.registerNewRequest();

        // Moving the request off the FIFO queue and into async tracking must not free
        // capacity: an unbounded number of deferred requests would otherwise be able to
        // pile up behind a single FIFO slot.
        pending.moveNextToAsyncResponses("42");
        assertThrows(ElastiCacheIntegrationsError.class, pending::registerNewRequest);

        RespValue deferredValue = new RespSimpleString("deferred".getBytes(StandardCharsets.UTF_8));
        assertTrue(pending.resolveAsync("42", deferredValue));
        assertEquals(deferredValue, deferred.join());

        // Once the deferred request resolves, its capacity is freed.
        CompletableFuture<RespValue> fifo = pending.registerNewRequest();
        assertNotNull(fifo);
    }

    @Test
    void moveNextToAsyncResponses_rejectsEmptyFifoAndDuplicateIdWithoutMutation() {
        PendingRequests pending = new PendingRequests();
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class, () -> pending.moveNextToAsyncResponses("42"));

        CompletableFuture<RespValue> first = pending.registerNewRequest();
        pending.moveNextToAsyncResponses("42");
        CompletableFuture<RespValue> second = pending.registerNewRequest();

        assertThrows(
                ElastiCacheIntegrationsProtocolError.class, () -> pending.moveNextToAsyncResponses("42"));
        RespValue value = new RespSimpleString("second".getBytes(StandardCharsets.UTF_8));
        assertTrue(pending.resolveOldestRequest(value));
        assertEquals(value, second.join());
        assertFalse(first.isDone());
    }

    @Test
    void moveNextToAsyncResponses_rejectsDuplicateIdForCancelledFifoRequest() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> active = pending.registerNewRequest();
        pending.moveNextToAsyncResponses("42");
        CompletableFuture<RespValue> cancelled = pending.registerNewRequest();
        pending.cancel(cancelled, new ElastiCacheIntegrationsRequestCancelled());

        assertThrows(
                ElastiCacheIntegrationsProtocolError.class, () -> pending.moveNextToAsyncResponses("42"));
        assertEquals(1, pending.size());

        RespValue value = new RespSimpleString("active".getBytes(StandardCharsets.UTF_8));
        assertTrue(pending.resolveAsync("42", value));
        assertEquals(value, active.join());
    }

    @Test
    void resolveAndRemoveAsync_unknownIdsAndFutureIdentity() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();

        assertFalse(pending.removeFromAsync(future));
        pending.moveNextToAsyncResponses(Long.toString(Long.MAX_VALUE));
        assertFalse(
                pending.resolveAsync(
                        "unknown", new RespSimpleString("ignored".getBytes(StandardCharsets.UTF_8))));
        assertTrue(pending.removeFromAsync(future));
        assertFalse(pending.removeFromAsync(future));
        assertFalse(
                pending.resolveAsync(
                        Long.toString(Long.MAX_VALUE),
                        new RespSimpleString("late".getBytes(StandardCharsets.UTF_8))));
        assertFalse(future.isDone());
    }

    @Test
    void timeoutDisposition_whenPromotionWins_removesAsyncWithoutClosing() {
        PendingRequests pending = new PendingRequests(1);
        AtomicInteger removals = new AtomicInteger();
        CompletableFuture<RespValue> future =
                pending.registerNewRequest(true, removals::incrementAndGet);
        pending.moveNextToAsyncResponses("42");

        ElastiCacheIntegrationsTimeoutError timeout =
                new ElastiCacheIntegrationsTimeoutError("timed out");

        assertEquals(
                PendingRequests.TimeoutDisposition.ASYNC_REMOVED, pending.onTimeout(future, timeout));
        assertFalse(pending.isClosed());
        assertFalse(future.isDone());
        assertFalse(
                pending.resolveAsync("42", new RespSimpleString("late".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, removals.get());
        assertNotNull(pending.registerNewRequest());
    }

    @Test
    void timeoutDisposition_whenTimeoutWins_onlyTimesOutThatRequest() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> timedOut = pending.registerNewRequest();
        CompletableFuture<RespValue> waiting = pending.registerNewRequest();
        ElastiCacheIntegrationsTimeoutError timeout =
                new ElastiCacheIntegrationsTimeoutError("timed out");

        assertEquals(PendingRequests.TimeoutDisposition.FATAL, pending.onTimeout(timedOut, timeout));

        assertFalse(pending.isClosed());
        assertEquals(timeout, assertThrows(ExecutionException.class, timedOut::get).getCause());
        assertFalse(waiting.isDone());

        ElastiCacheIntegrationsConnectionLost connectionLost =
                new ElastiCacheIntegrationsConnectionLost("connection closed");
        pending.failAll(connectionLost);

        assertTrue(pending.isClosed());
        assertEquals(connectionLost, assertThrows(ExecutionException.class, waiting::get).getCause());
    }

    @Test
    void timeoutDisposition_completedFutureDoesNotClosePending() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        RespValue response = new RespSimpleString("complete".getBytes(StandardCharsets.UTF_8));
        pending.resolveOldestRequest(response);

        assertEquals(
                PendingRequests.TimeoutDisposition.COMPLETED,
                pending.onTimeout(future, new ElastiCacheIntegrationsTimeoutError("timed out")));
        assertFalse(pending.isClosed());
        assertEquals(response, future.join());
    }

    @Test
    void cancel_beforePromotionRetainsFifoPositionWithoutAsyncLeak() {
        PendingRequests pending = new PendingRequests();
        AtomicInteger removals = new AtomicInteger();
        CompletableFuture<RespValue> future =
                pending.registerNewRequest(true, removals::incrementAndGet);
        ElastiCacheIntegrationsRequestCancelled cancellation =
                new ElastiCacheIntegrationsRequestCancelled();

        pending.cancel(future, cancellation);

        assertEquals(1, pending.size());
        assertEquals(0, removals.get());
        assertEquals(cancellation, assertThrows(ExecutionException.class, future::get).getCause());
        pending.moveNextToAsyncResponses("42");
        assertEquals(0, pending.size());
        assertEquals(1, removals.get());
        assertFalse(
                pending.resolveAsync("42", new RespSimpleString("late".getBytes(StandardCharsets.UTF_8))));
        assertFalse(pending.isClosed());
    }

    @Test
    void cancel_afterPromotionRemovesAsyncResponse() {
        PendingRequests pending = new PendingRequests(1);
        AtomicInteger removals = new AtomicInteger();
        CompletableFuture<RespValue> future =
                pending.registerNewRequest(true, removals::incrementAndGet);
        pending.moveNextToAsyncResponses("42");
        ElastiCacheIntegrationsRequestCancelled cancellation =
                new ElastiCacheIntegrationsRequestCancelled();

        pending.cancel(future, cancellation);

        assertEquals(cancellation, assertThrows(ExecutionException.class, future::get).getCause());
        assertEquals(1, removals.get());
        assertFalse(
                pending.resolveAsync("42", new RespSimpleString("late".getBytes(StandardCharsets.UTF_8))));
        assertFalse(pending.isClosed());
        assertNotNull(pending.registerNewRequest());
    }

    @Test
    void failAll_completesAllWithException() throws Exception {
        PendingRequests pending = new PendingRequests();

        CompletableFuture<RespValue> f1 = pending.registerNewRequest();
        CompletableFuture<RespValue> f2 = pending.registerNewRequest();
        CompletableFuture<RespValue> f3 = pending.registerNewRequest();

        ElastiCacheIntegrationsConnectionLost cause =
                new ElastiCacheIntegrationsConnectionLost("test failure");
        pending.failAll(cause);

        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
        assertTrue(f3.isCompletedExceptionally());

        ExecutionException e1 = assertThrows(ExecutionException.class, f1::get);
        assertEquals(cause, e1.getCause());
    }

    @Test
    void failAll_completesFifoAndAsyncRequests() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> deferred = pending.registerNewRequest();
        pending.moveNextToAsyncResponses("-1");
        CompletableFuture<RespValue> fifo = pending.registerNewRequest();

        ElastiCacheIntegrationsConnectionLost cause =
                new ElastiCacheIntegrationsConnectionLost("test failure");
        pending.failAll(cause);

        assertEquals(cause, assertThrows(ExecutionException.class, deferred::get).getCause());
        assertEquals(cause, assertThrows(ExecutionException.class, fifo::get).getCause());
        assertFalse(
                pending.resolveAsync("-1", new RespSimpleString("late".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void failAll_marksClosed() {
        PendingRequests pending = new PendingRequests();
        assertFalse(pending.isClosed());

        pending.failAll(new ElastiCacheIntegrationsConnectionLost("test"));
        assertTrue(pending.isClosed());
    }

    @Test
    void register_afterFailAll_throwsElastiCacheIntegrationsConnectionLost() {
        PendingRequests pending = new PendingRequests();
        pending.failAll(new ElastiCacheIntegrationsConnectionLost("test"));

        assertThrows(ElastiCacheIntegrationsConnectionLost.class, pending::registerNewRequest);
    }

    @Test
    void register_atCapacity_throwsElastiCacheIntegrationsError() {
        PendingRequests pending = new PendingRequests(2);

        assertNotNull(pending.registerNewRequest());
        assertNotNull(pending.registerNewRequest());

        // Third should fail. Capacity exhaustion does not mean the connection was lost, so
        // this is the base ElastiCacheIntegrationsError, not ElastiCacheIntegrationsConnectionLost.
        ElastiCacheIntegrationsError e =
                assertThrows(ElastiCacheIntegrationsError.class, pending::registerNewRequest);
        assertFalse(e instanceof ElastiCacheIntegrationsConnectionLost);
        assertTrue(e.getMessage().contains("Too many pending"));
        assertTrue(e.getMessage().contains("max=2"));
    }

    @Test
    void register_afterResolve_allowsMore() {
        PendingRequests pending = new PendingRequests(2);

        assertNotNull(pending.registerNewRequest());
        assertNotNull(pending.registerNewRequest());

        // At capacity
        assertThrows(ElastiCacheIntegrationsError.class, pending::registerNewRequest);

        // Resolve one
        pending.resolveOldestRequest(new RespSimpleString("ok".getBytes(StandardCharsets.UTF_8)));

        // Now we can register again
        CompletableFuture<RespValue> f3 = pending.registerNewRequest();
        assertNotNull(f3);
    }

    @Test
    void size_tracksQueueLength() {
        PendingRequests pending = new PendingRequests();
        assertEquals(0, pending.size());

        assertNotNull(pending.registerNewRequest());
        assertEquals(1, pending.size());

        assertNotNull(pending.registerNewRequest());
        assertNotNull(pending.registerNewRequest());
        assertEquals(3, pending.size());

        pending.resolveOldestRequest(new RespSimpleString("ok".getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, pending.size());
    }

    @Test
    void constructor_invalidMaxPending_throws() {
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(0));
        assertThrows(IllegalArgumentException.class, () -> new PendingRequests(-1));
    }

    @Test
    void threadSafety_concurrentRegisterAndResolve() throws Exception {
        PendingRequests pending = new PendingRequests(1000);
        int numProducers = 4;
        int numPerProducer = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numProducers + 1);
        List<CompletableFuture<RespValue>> allFutures = new ArrayList<>();
        Object futuresLock = new Object();

        ExecutorService executor = Executors.newFixedThreadPool(numProducers + 1);

        // Producers: register futures
        for (int p = 0; p < numProducers; p++) {
            executor.execute(
                    () -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < numPerProducer; i++) {
                                CompletableFuture<RespValue> f = pending.registerNewRequest();
                                synchronized (futuresLock) {
                                    allFutures.add(f);
                                }
                            }
                        } catch (Exception e) {
                            // ignore
                        } finally {
                            doneLatch.countDown();
                        }
                    });
        }

        // Consumer: resolve futures
        executor.execute(
                () -> {
                    try {
                        startLatch.await();
                        int resolved = 0;
                        int maxAttempts = numProducers * numPerProducer * 10;
                        while (resolved < numProducers * numPerProducer && maxAttempts-- > 0) {
                            RespValue v =
                                    new RespSimpleString(("val" + resolved).getBytes(StandardCharsets.UTF_8));
                            if (pending.resolveOldestRequest(v)) {
                                resolved++;
                            } else {
                                Thread.sleep(1);
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        doneLatch.countDown();
                    }
                });

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // All futures should be completed
        synchronized (futuresLock) {
            assertEquals(numProducers * numPerProducer, allFutures.size());
            for (CompletableFuture<RespValue> f : allFutures) {
                assertTrue(f.isDone());
                assertFalse(f.isCompletedExceptionally());
            }
        }
    }
}
