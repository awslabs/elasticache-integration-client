package software.amazon.photon.connection;

import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PendingRequests.
 */
class PendingRequestsTest {

    @Test
    void register_returnsFuture() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.register();
        assertNotNull(future);
        assertFalse(future.isDone());
    }

    @Test
    void resolveNext_completesOldestFuture() {
        PendingRequests pending = new PendingRequests();

        CompletableFuture<RespValue> f1 = pending.register();
        CompletableFuture<RespValue> f2 = pending.register();
        CompletableFuture<RespValue> f3 = pending.register();

        RespValue v1 = new RespSimpleString("one".getBytes(StandardCharsets.UTF_8));
        RespValue v2 = new RespSimpleString("two".getBytes(StandardCharsets.UTF_8));
        RespValue v3 = new RespSimpleString("three".getBytes(StandardCharsets.UTF_8));

        // Resolve in FIFO order
        assertTrue(pending.resolveNext(v1));
        assertTrue(f1.isDone());
        assertFalse(f2.isDone());
        assertFalse(f3.isDone());
        assertEquals(v1, f1.join());

        assertTrue(pending.resolveNext(v2));
        assertTrue(f2.isDone());
        assertFalse(f3.isDone());
        assertEquals(v2, f2.join());

        assertTrue(pending.resolveNext(v3));
        assertTrue(f3.isDone());
        assertEquals(v3, f3.join());
    }

    @Test
    void resolveNext_emptyQueue_returnsFalse() {
        PendingRequests pending = new PendingRequests();
        RespValue v = new RespSimpleString("test".getBytes(StandardCharsets.UTF_8));
        assertFalse(pending.resolveNext(v));
    }

    @Test
    void failAll_completesAllWithException() throws Exception {
        PendingRequests pending = new PendingRequests();

        CompletableFuture<RespValue> f1 = pending.register();
        CompletableFuture<RespValue> f2 = pending.register();
        CompletableFuture<RespValue> f3 = pending.register();

        ConnectionLost cause = new ConnectionLost("test failure");
        pending.failAll(cause);

        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
        assertTrue(f3.isCompletedExceptionally());

        ExecutionException e1 = assertThrows(ExecutionException.class, f1::get);
        assertEquals(cause, e1.getCause());
    }

    @Test
    void failAll_marksClosed() {
        PendingRequests pending = new PendingRequests();
        assertFalse(pending.isClosed());

        pending.failAll(new ConnectionLost("test"));
        assertTrue(pending.isClosed());
    }

    @Test
    void register_afterFailAll_throwsConnectionLost() {
        PendingRequests pending = new PendingRequests();
        pending.failAll(new ConnectionLost("test"));

        assertThrows(ConnectionLost.class, pending::register);
    }

    @Test
    void register_atCapacity_throwsConnectionLost() {
        PendingRequests pending = new PendingRequests(2);

        pending.register();
        pending.register();

        // Third should fail
        ConnectionLost e = assertThrows(ConnectionLost.class, pending::register);
        assertTrue(e.getMessage().contains("Too many pending"));
        assertTrue(e.getMessage().contains("max=2"));
    }

    @Test
    void register_afterResolve_allowsMore() {
        PendingRequests pending = new PendingRequests(2);

        CompletableFuture<RespValue> f1 = pending.register();
        pending.register();

        // At capacity
        assertThrows(ConnectionLost.class, pending::register);

        // Resolve one
        pending.resolveNext(new RespSimpleString("ok".getBytes(StandardCharsets.UTF_8)));

        // Now we can register again
        CompletableFuture<RespValue> f3 = pending.register();
        assertNotNull(f3);
    }

    @Test
    void size_tracksQueueLength() {
        PendingRequests pending = new PendingRequests();
        assertEquals(0, pending.size());

        pending.register();
        assertEquals(1, pending.size());

        pending.register();
        pending.register();
        assertEquals(3, pending.size());

        pending.resolveNext(new RespSimpleString("ok".getBytes(StandardCharsets.UTF_8)));
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
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < numPerProducer; i++) {
                        CompletableFuture<RespValue> f = pending.register();
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
        executor.submit(() -> {
            try {
                startLatch.await();
                int resolved = 0;
                int maxAttempts = numProducers * numPerProducer * 10;
                while (resolved < numProducers * numPerProducer && maxAttempts-- > 0) {
                    RespValue v = new RespSimpleString(("val" + resolved).getBytes(StandardCharsets.UTF_8));
                    if (pending.resolveNext(v)) {
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
