package software.amazon.photon.session;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Reconnect and origin-fallback settings for a Photon session.
 *
 * <p>Initial connection attempts are bounded. After an established connection is lost, reconnect
 * attempts continue until one succeeds or the session is closed. The same backoff schedule is used
 * when background AUTH refresh fails.
 */
public final class ReconnectPolicy {

    public static final long UNLIMITED_FALLBACK = -1;
    public static final int DEFAULT_CONNECT_ATTEMPTS = 3;
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 5_000;
    public static final long DEFAULT_AUTH_REFRESH_INTERVAL_MS = 900_000;
    public static final long DEFAULT_BASE_DELAY_MS = 100;
    public static final long DEFAULT_MAX_DELAY_MS = 3_200;
    public static final double DEFAULT_JITTER_FACTOR = 0.2;

    private final long fallbackDurationSeconds;
    private final int connectAttempts;
    private final long connectionTimeoutMs;
    private final long authRefreshIntervalMs;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;

    private ReconnectPolicy(Builder builder) {
        this.fallbackDurationSeconds = builder.fallbackDurationSeconds;
        this.connectAttempts = builder.connectAttempts;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.authRefreshIntervalMs = builder.authRefreshIntervalMs;
        this.baseDelayMs = builder.baseDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.jitterFactor = builder.jitterFactor;
    }

    public static ReconnectPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Return the origin-fallback window in seconds; negative means unlimited. */
    public long getFallbackDurationSeconds() {
        return fallbackDurationSeconds;
    }

    /** Return the maximum number of initial connection attempts. */
    public int getConnectAttempts() {
        return connectAttempts;
    }

    /** Return the timeout for one complete connection initialization attempt. */
    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    /**
     * Return how often a live connection obtains fresh credentials and runs AUTH again, in
     * milliseconds.
     */
    public long getAuthRefreshIntervalMs() {
        return authRefreshIntervalMs;
    }

    /** Return the first reconnect backoff delay. */
    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    /** Return the reconnect backoff ceiling before jitter. */
    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    /** Return the symmetric jitter factor applied to each backoff delay. */
    public double getJitterFactor() {
        return jitterFactor;
    }

    long delayFor(long attempt) {
        long delay = baseDelayMs;
        for (long i = 1; i < attempt && delay < maxDelayMs; i++) {
            delay = Math.min(maxDelayMs, delay > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : delay * 2);
        }
        if (jitterFactor == 0) {
            return delay;
        }
        double multiplier = ThreadLocalRandom.current().nextDouble(1 - jitterFactor, 1 + jitterFactor);
        return Math.max(1, Math.round(delay * multiplier));
    }

    public static final class Builder {
        private long fallbackDurationSeconds = UNLIMITED_FALLBACK;
        private int connectAttempts = DEFAULT_CONNECT_ATTEMPTS;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private long authRefreshIntervalMs = DEFAULT_AUTH_REFRESH_INTERVAL_MS;
        private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
        private long maxDelayMs = DEFAULT_MAX_DELAY_MS;
        private double jitterFactor = DEFAULT_JITTER_FACTOR;

        private Builder() {}

        /**
         * Set how long requests may use origin fallback while reconnecting.
         *
         * <p>A negative value allows fallback indefinitely, zero disables fallback, and a positive
         * value allows fallback for that many seconds.
         */
        public Builder fallbackDurationSeconds(long fallbackDurationSeconds) {
            this.fallbackDurationSeconds = fallbackDurationSeconds;
            return this;
        }

        /** Set the maximum number of initial connection attempts. */
        public Builder connectAttempts(int connectAttempts) {
            this.connectAttempts = connectAttempts;
            return this;
        }

        /** Set the timeout for one complete connection initialization attempt. */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * Set how often a live connection obtains fresh credentials and runs AUTH again, in
         * milliseconds.
         */
        public Builder authRefreshIntervalMs(long authRefreshIntervalMs) {
            this.authRefreshIntervalMs = authRefreshIntervalMs;
            return this;
        }

        /** Set the first reconnect backoff delay. */
        public Builder baseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
            return this;
        }

        /** Set the reconnect backoff ceiling before jitter. */
        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        /** Set symmetric reconnect jitter in the range {@code [0, 1)}. */
        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        public ReconnectPolicy build() {
            requirePositive("connectAttempts", connectAttempts);
            requirePositive("connectionTimeoutMs", connectionTimeoutMs);
            requirePositive("authRefreshIntervalMs", authRefreshIntervalMs);
            requirePositive("baseDelayMs", baseDelayMs);
            requirePositive("maxDelayMs", maxDelayMs);
            if (maxDelayMs < baseDelayMs) {
                throw new IllegalArgumentException("maxDelayMs must be at least baseDelayMs");
            }
            if (Double.isNaN(jitterFactor) || jitterFactor < 0 || jitterFactor >= 1) {
                throw new IllegalArgumentException("jitterFactor must be in [0, 1)");
            }
            return new ReconnectPolicy(this);
        }

        private static void requirePositive(String name, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
