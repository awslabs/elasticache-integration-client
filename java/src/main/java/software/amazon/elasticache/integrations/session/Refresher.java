package software.amazon.elasticache.integrations.session;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic periodic-callback runner used to drive background refresh tasks.
 *
 * <p>Knows nothing about Cache, sessions, or config: it runs a no-arg callback on a jittered
 * interval, in a named daemon thread, until told to stop. Callback failures use an optional retry
 * delay and never terminate the loop.
 *
 * <p>The loop blocks on a {@link CountDownLatch} rather than sleeping, so {@link #stop()}
 * interrupts an in-progress wait immediately instead of waiting it out. Each steady-state cycle
 * recalculates its configured symmetric jitter.
 *
 * <p>{@code jitterFactor}, {@code random}, and {@code waiter} are injectable so tests can disable
 * randomness, seed it, and substitute a fake clock. Production code uses the defaults (real jitter,
 * real waiting).
 *
 * <p>Package-private: this is internal machinery and is not part of the public API.
 */
class Refresher {

    private static final Logger LOGGER = Logger.getLogger(Refresher.class.getName());

    /** Default bound on how long {@link #stop()} waits for the thread to exit. */
    private static final long DEFAULT_STOP_TIMEOUT_MILLIS = 5000;

    /**
     * Test seam for the blocking wait. Mirrors {@code CountDownLatch.await}: returns true when the
     * refresher has been stopped, false when the timeout elapsed and another cycle is due.
     */
    interface Waiter {
        boolean await(CountDownLatch stop, double timeoutSeconds) throws InterruptedException;
    }

    interface FailureDelay {
        double delaySeconds(long consecutiveFailures);
    }

    private volatile double intervalSeconds;
    private final Runnable refreshOnce;
    private final String name;
    private final double jitterFactor;
    private final FailureDelay failureDelay;
    private final Random random;
    private final Waiter waiter;

    private final CountDownLatch stop = new CountDownLatch(1);
    private volatile Thread thread;

    Refresher(double intervalSeconds, Runnable refreshOnce) {
        this(
                intervalSeconds,
                refreshOnce,
                "aws-elasticache-integration-client-refresher",
                ReconnectPolicy.DEFAULT_JITTER_FACTOR,
                null);
    }

    Refresher(
            double intervalSeconds, Runnable refreshOnce, boolean jitter, Random random, Waiter waiter) {
        this(
                intervalSeconds,
                refreshOnce,
                "aws-elasticache-integration-client-refresher",
                jitter ? ReconnectPolicy.DEFAULT_JITTER_FACTOR : 0,
                null,
                random,
                waiter);
    }

    /**
     * Create a refresher that runs {@code refreshOnce} every {@code intervalSeconds} (± jitter).
     *
     * @param intervalSeconds the base interval between callbacks, in seconds
     * @param refreshOnce the callback to run on each cycle
     */
    Refresher(
            double intervalSeconds,
            Runnable refreshOnce,
            String name,
            double jitterFactor,
            FailureDelay failureDelay) {
        this(intervalSeconds, refreshOnce, name, jitterFactor, failureDelay, new Random(), null);
    }

    /** Test constructor. {@code waiter} may be null to use the real blocking wait. */
    Refresher(
            double intervalSeconds,
            Runnable refreshOnce,
            String name,
            double jitterFactor,
            FailureDelay failureDelay,
            Random random,
            Waiter waiter) {
        this.intervalSeconds = intervalSeconds;
        this.refreshOnce = refreshOnce;
        this.name = name;
        this.jitterFactor = jitterFactor;
        this.failureDelay = failureDelay;
        this.random = random;
        this.waiter = waiter != null ? waiter : Refresher::realAwait;
    }

    /** Start the refresh loop on a new daemon thread. */
    void start() {
        Thread refreshThread = new Thread(this::refreshLoop, name);
        refreshThread.setDaemon(true);
        this.thread = refreshThread;
        refreshThread.start();
    }

    /**
     * Signal the loop to stop, interrupt an active callback, and wait up to five seconds for the
     * thread to exit. Idempotent.
     */
    void stop() {
        stop(DEFAULT_STOP_TIMEOUT_MILLIS);
    }

    /**
     * Signal the loop to stop and wait up to {@code timeoutMillis} for the thread to exit.
     *
     * <p>Idempotent: counting down an already-open latch and joining an already-dead thread are both
     * no-ops. An active callback is interrupted so an interruptible callback can finish before the
     * join timeout expires. When called from the refresher thread itself, the latch still ends the
     * loop after the current callback, but interrupt and join are skipped to avoid self-interruption
     * and self-join. If the joining thread is interrupted, its interrupt status is restored rather
     * than swallowed.
     */
    void stop(long timeoutMillis) {
        stop.countDown();
        Thread refreshThread = this.thread;
        if (refreshThread != null
                && refreshThread.isAlive()
                && refreshThread != Thread.currentThread()) {
            refreshThread.interrupt();
            try {
                refreshThread.join(timeoutMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Change the interval used for all subsequent delays.
     *
     * <p>Validated before assignment, so an invalid interval leaves the previous one intact. Takes
     * effect starting with the next call to {@link #nextRefreshDelaySeconds()}; a wait already in
     * progress keeps using the delay it was given.
     *
     * @throws IllegalArgumentException if {@code intervalSeconds} is not positive
     */
    void setInterval(double intervalSeconds) {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("intervalSeconds must be positive: " + intervalSeconds);
        }
        this.intervalSeconds = intervalSeconds;
    }

    private void refreshLoop() {
        double delaySeconds = nextRefreshDelaySeconds();
        long consecutiveFailures = 0;
        try {
            while (!waiter.await(stop, delaySeconds)) {
                try {
                    refreshOnce.run();
                    consecutiveFailures = 0;
                    delaySeconds = nextRefreshDelaySeconds();
                } catch (RuntimeException e) {
                    consecutiveFailures++;
                    Level level =
                            consecutiveFailures == 1 || consecutiveFailures % 100 == 0
                                    ? Level.WARNING
                                    : Level.FINE;
                    LOGGER.log(
                            level,
                            "Cache background task "
                                    + name
                                    + " failed ("
                                    + consecutiveFailures
                                    + " in a row); retrying. The cache keeps serving meanwhile.",
                            e);
                    delaySeconds =
                            failureDelay != null
                                    ? failureDelay.delaySeconds(consecutiveFailures)
                                    : nextRefreshDelaySeconds();
                }
            }
        } catch (InterruptedException e) {
            // Interruption is treated as a stop signal; restore the flag and exit the loop.
            Thread.currentThread().interrupt();
        }
    }

    /** The delay before the next cycle, freshly jittered. Package-private for tests. */
    double nextRefreshDelaySeconds() {
        if (jitterFactor == 0) {
            return intervalSeconds;
        }
        return intervalSeconds * ((1 - jitterFactor) + random.nextDouble() * (2 * jitterFactor));
    }

    /** The refresh thread, or null before {@link #start()}. Package-private for tests. */
    Thread thread() {
        return thread;
    }

    private static boolean realAwait(CountDownLatch stop, double timeoutSeconds)
            throws InterruptedException {
        long nanos = (long) (timeoutSeconds * TimeUnit.SECONDS.toNanos(1));
        return stop.await(nanos, TimeUnit.NANOSECONDS);
    }
}
