package software.amazon.photon.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for ConfigRefresher.
 *
 * <p>ConfigRefresher is a generic periodic-callback runner with no dependency on PhotonSession,
 * PhotonConnector, or any Photon protocol module, so these tests exercise it in isolation with a
 * plain counting callback. Most tests disable jitter and/or inject a fake {@code Waiter} so no
 * test depends on a real sleep; two exercise the real defaults end to end.
 *
 * <p>Every refresher created here is registered for teardown so a failing assertion cannot leak
 * a refresher thread.
 */
class ConfigRefresherTest {

    private final List<ConfigRefresher> started = new ArrayList<>();

    @AfterEach
    void stopAll() {
        for (ConfigRefresher refresher : started) {
            refresher.stop();
        }
        for (ConfigRefresher refresher : started) {
            Thread thread = refresher.thread();
            if (thread != null) {
                assertFalse(thread.isAlive(), "refresher thread leaked: " + thread.getName());
            }
        }
    }

    /** Track a refresher for teardown and return it. */
    private ConfigRefresher track(ConfigRefresher refresher) {
        started.add(refresher);
        return refresher;
    }

    /**
     * A Waiter stand-in: reports "not stopped" {@code stopAfter} times, then "stopped", recording
     * every requested timeout so a test can assert the exact delays with no real waiting.
     */
    private static final class FakeWaiter implements ConfigRefresher.Waiter {
        private final int stopAfter;
        private final List<Double> timeouts = new ArrayList<>();

        FakeWaiter(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        @Override
        public synchronized boolean await(CountDownLatch stop, double timeoutSeconds) {
            timeouts.add(timeoutSeconds);
            return timeouts.size() > stopAfter;
        }

        synchronized List<Double> timeouts() {
            return new ArrayList<>(timeouts);
        }
    }

    @Test
    void start_invokesCallbackAfterAnInterval() throws Exception {
        // Exercises the real default waiter (CountDownLatch.await) end to end, not the fake.
        CountDownLatch invoked = new CountDownLatch(1);
        ConfigRefresher refresher = track(new ConfigRefresher(0.01, invoked::countDown));
        refresher.start();

        assertTrue(invoked.await(5, TimeUnit.SECONDS), "callback was never invoked");
        refresher.stop();
    }

    @Test
    void injectedWaiter_drivesAnExactCallbackCount() throws Exception {
        AtomicInteger count = new AtomicInteger();
        FakeWaiter waiter = new FakeWaiter(3);
        ConfigRefresher refresher = track(new ConfigRefresher(
                60, count::incrementAndGet, false, new Random(), waiter));
        refresher.start();
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(3, count.get());
        assertEquals(4, waiter.timeouts().size(), "one wait per cycle plus the stopping wait");
    }

    @Test
    void stop_wakesAndTerminatesThreadPromptly() {
        // A 100-second interval: only an interruptible wait can make stop() return quickly.
        ConfigRefresher refresher = track(new ConfigRefresher(100, () -> { }));
        refresher.start();

        long startNanos = System.nanoTime();
        refresher.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertFalse(refresher.thread().isAlive());
        assertTrue(elapsedMillis < 1000, "stop() took " + elapsedMillis + "ms");
    }

    @Test
    void stop_interruptsAnInFlightCallback() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        ConfigRefresher refresher = track(new ConfigRefresher(0.01, () -> {
            callbackStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                callbackInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }));
        refresher.start();

        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS), "callback did not start");
        refresher.stop(1000);

        assertTrue(callbackInterrupted.await(1, TimeUnit.SECONDS),
                "stop() did not interrupt the active callback");
        assertFalse(refresher.thread().isAlive());
    }

    @Test
    void callbackFailure_doesNotStopLaterRefreshes() throws Exception {
        AtomicInteger invocations = new AtomicInteger();
        FakeWaiter waiter = new FakeWaiter(3);
        ConfigRefresher refresher = track(new ConfigRefresher(60, () -> {
            invocations.incrementAndGet();
            throw new RuntimeException("refresh failed");
        }, false, new Random(), waiter));

        refresher.start();
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(3, invocations.get());
        assertFalse(refresher.thread().isAlive());
    }

    @Test
    void stop_isIdempotent() {
        ConfigRefresher refresher = track(new ConfigRefresher(100, () -> { }));
        refresher.start();

        refresher.stop();
        refresher.stop();
        refresher.stop();

        assertFalse(refresher.thread().isAlive());
    }

    @Test
    void stop_beforeStart_doesNotThrow() {
        ConfigRefresher refresher = track(new ConfigRefresher(100, () -> { }));
        refresher.stop();
        assertNull(refresher.thread());
    }

    @Test
    void thread_isDaemonAndNamed() {
        ConfigRefresher refresher = track(new ConfigRefresher(100, () -> { }));
        refresher.start();

        assertNotNull(refresher.thread());
        assertTrue(refresher.thread().isDaemon());
        assertEquals("photon-refresher", refresher.thread().getName());
    }

    @Test
    void nextRefreshDelay_withJitterDisabled_isExact() {
        ConfigRefresher refresher = new ConfigRefresher(10, () -> { }, false, new Random(), null);
        for (int i = 0; i < 5; i++) {
            assertEquals(10.0, refresher.nextRefreshDelaySeconds());
        }
    }

    @Test
    void nextRefreshDelay_withJitter_staysWithinTwentyPercent() {
        ConfigRefresher refresher = new ConfigRefresher(10, () -> { });
        for (int i = 0; i < 500; i++) {
            double delay = refresher.nextRefreshDelaySeconds();
            assertTrue(delay >= 8.0 && delay <= 12.0, "delay out of ±20% bounds: " + delay);
        }
    }

    @Test
    void jitter_isRecalculatedEachCycle() throws Exception {
        // A seeded Random makes the per-cycle delays deterministic yet distinct.
        FakeWaiter waiter = new FakeWaiter(4);
        ConfigRefresher refresher = track(new ConfigRefresher(
                10, () -> { }, true, new Random(42), waiter));
        refresher.start();
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));

        List<Double> timeouts = waiter.timeouts();
        assertEquals(5, timeouts.size());
        for (double delay : timeouts) {
            assertTrue(delay >= 8.0 && delay <= 12.0, "delay out of ±20% bounds: " + delay);
        }
        assertTrue(timeouts.stream().distinct().count() > 1,
                "jitter was not recalculated across cycles: " + timeouts);
    }

    // === setInterval ===

    @Test
    void setInterval_changesNextComputedDelay() {
        ConfigRefresher refresher = new ConfigRefresher(10, () -> { }, false, new Random(), null);
        assertEquals(10.0, refresher.nextRefreshDelaySeconds());
        refresher.setInterval(30);
        assertEquals(30.0, refresher.nextRefreshDelaySeconds());
    }

    @Test
    void setInterval_rejectsNonPositiveAndLeavesPreviousValueIntact() {
        ConfigRefresher refresher = new ConfigRefresher(10, () -> { }, false, new Random(), null);
        assertThrows(IllegalArgumentException.class, () -> refresher.setInterval(0));
        assertThrows(IllegalArgumentException.class, () -> refresher.setInterval(-5));
        assertEquals(10.0, refresher.nextRefreshDelaySeconds());
    }

    @Test
    void setInterval_doesNotAffectAWaitAlreadyInProgress() throws Exception {
        // A delay already handed to the waiter before setInterval() must not change retroactively;
        // only delays computed afterward use the new interval.
        FakeWaiter waiter = new FakeWaiter(2);
        ConfigRefresher refresher = track(new ConfigRefresher(10, () -> { }, false, new Random(), waiter));
        refresher.setInterval(99); // before start(): every computed delay uses the new interval
        refresher.start();
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(Arrays.asList(99.0, 99.0, 99.0), waiter.timeouts());
    }

    @Test
    void setInterval_calledFromCallback_appliesStartingNextCycleOnly() throws Exception {
        // The first delay is already computed and handed to the waiter before the callback (and
        // its setInterval call) runs, so only the delays computed afterward pick up the change.
        FakeWaiter waiter = new FakeWaiter(2);
        ConfigRefresher[] holder = new ConfigRefresher[1];
        ConfigRefresher refresher = new ConfigRefresher(
                10, () -> holder[0].setInterval(99), false, new Random(), waiter);
        holder[0] = refresher;
        track(refresher);
        refresher.start();
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));

        assertEquals(Arrays.asList(10.0, 99.0, 99.0), waiter.timeouts());
    }

    // === Self-join guard ===

    @Test
    void stop_calledFromWithinTheCallback_doesNotDeadlock() throws Exception {
        // Simulates PhotonSession's refresh callback stopping the refresher after observing a
        // dead connector: stop() must not try to join the thread it is running on.
        CountDownLatch stopped = new CountDownLatch(1);
        ConfigRefresher[] holder = new ConfigRefresher[1];
        ConfigRefresher refresher = new ConfigRefresher(0.01, () -> {
            holder[0].stop();
            stopped.countDown();
        });
        holder[0] = refresher;
        track(refresher);
        refresher.start();

        assertTrue(stopped.await(5, TimeUnit.SECONDS), "self-stop() call blocked");
        refresher.thread().join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(refresher.thread().isAlive());
    }
}
