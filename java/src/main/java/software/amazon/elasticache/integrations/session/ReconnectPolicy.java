// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import java.util.concurrent.ThreadLocalRandom;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;

/**
 * Reconnect and origin-fallback settings for a Cache session.
 *
 * <p>The connection timeout bounds the first connection: startup keeps trying until it has passed.
 * It also sets how long a request waits for a reconnect after an established connection is lost.
 * The background reconnect continues until one attempt succeeds or the session is closed. The same
 * backoff schedule is used when background AUTH refresh fails.
 */
final class ReconnectPolicy {

    /**
     * {@link Builder#fallbackDurationSeconds(long)} value that allows origin fallback indefinitely.
     */
    public static final long UNLIMITED_FALLBACK = -1;

    /**
     * Default limit, in milliseconds, on how long startup keeps trying to open the first connection
     * and how long a request waits for a reconnect.
     */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS =
            ElastiCacheIntegrationsConnector.DEFAULT_CONNECTION_TIMEOUT_MS;

    /** Cap on one background reconnect attempt, in milliseconds. */
    public static final long RECONNECT_ATTEMPT_TIMEOUT_MS = 5_000;

    /** Default interval between AUTH refreshes on a live connection, in milliseconds (15 minutes). */
    public static final long DEFAULT_AUTH_REFRESH_INTERVAL_MS = 900_000;

    /** Default first reconnect backoff delay, in milliseconds. */
    public static final long DEFAULT_BASE_DELAY_MS = 100;

    /** Default reconnect backoff ceiling before jitter, in milliseconds. */
    public static final long DEFAULT_MAX_DELAY_MS = 3_200;

    /** Default symmetric jitter factor applied to each backoff delay. */
    public static final double DEFAULT_JITTER_FACTOR = 0.2;

    private final long fallbackDurationSeconds;
    private final long connectionTimeoutMs;
    private final long authRefreshIntervalMs;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;

    private ReconnectPolicy(Builder builder) {
        this.fallbackDurationSeconds = builder.fallbackDurationSeconds;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.authRefreshIntervalMs = builder.authRefreshIntervalMs;
        this.baseDelayMs = builder.baseDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.jitterFactor = builder.jitterFactor;
    }

    /**
     * Return a policy with every default.
     *
     * @return the default policy
     */
    public static ReconnectPolicy defaults() {
        return builder().build();
    }

    /**
     * Create a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Return the origin-fallback window in seconds; negative means unlimited.
     *
     * @return the fallback window in seconds, or a negative value for unlimited
     */
    public long getFallbackDurationSeconds() {
        return fallbackDurationSeconds;
    }

    /**
     * Return how long startup keeps trying to open the first connection, and how long a request waits
     * for a reconnect after a loss.
     *
     * @return the timeout in milliseconds
     */
    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    /**
     * Return how often a live connection obtains fresh credentials and runs AUTH again, in
     * milliseconds.
     *
     * @return the AUTH refresh interval in milliseconds
     */
    public long getAuthRefreshIntervalMs() {
        return authRefreshIntervalMs;
    }

    /**
     * Return the first reconnect backoff delay.
     *
     * @return the first backoff delay in milliseconds
     */
    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    /**
     * Return the reconnect backoff ceiling before jitter.
     *
     * @return the backoff ceiling in milliseconds
     */
    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    /**
     * Return the symmetric jitter factor applied to each backoff delay.
     *
     * @return the jitter factor
     */
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

    /** Builder for {@link ReconnectPolicy}. Every setting starts at its default. */
    public static final class Builder {
        private long fallbackDurationSeconds = UNLIMITED_FALLBACK;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private long authRefreshIntervalMs = DEFAULT_AUTH_REFRESH_INTERVAL_MS;
        private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
        private long maxDelayMs = DEFAULT_MAX_DELAY_MS;
        private double jitterFactor = DEFAULT_JITTER_FACTOR;

        private Builder() {}

        /**
         * Set how long, in seconds, routed requests may use the origin service after the connection is
         * lost. The window is shared by requests during one outage. A later outage gets a new window
         * after recovery.
         *
         * <p>The window starts when the reconnect wait ends. A negative value allows fallback
         * indefinitely, zero disables fallback so a request raises once the wait ends, and a positive
         * value allows fallback for that many seconds.
         *
         * @param fallbackDurationSeconds the fallback window in seconds
         * @return this builder
         */
        public Builder fallbackDurationSeconds(long fallbackDurationSeconds) {
            this.fallbackDurationSeconds = fallbackDurationSeconds;
            return this;
        }

        /**
         * Set how long startup keeps trying to open the first connection, and how long a request waits
         * for a reconnect after a loss.
         *
         * @param connectionTimeoutMs the timeout in milliseconds
         * @return this builder
         */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * Set how often a live connection obtains fresh credentials and runs AUTH again, in
         * milliseconds.
         *
         * @param authRefreshIntervalMs the AUTH refresh interval in milliseconds
         * @return this builder
         */
        public Builder authRefreshIntervalMs(long authRefreshIntervalMs) {
            this.authRefreshIntervalMs = authRefreshIntervalMs;
            return this;
        }

        /**
         * Set the first reconnect backoff delay.
         *
         * @param baseDelayMs the first backoff delay in milliseconds
         * @return this builder
         */
        public Builder baseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
            return this;
        }

        /**
         * Set the reconnect backoff ceiling before jitter.
         *
         * @param maxDelayMs the backoff ceiling in milliseconds
         * @return this builder
         */
        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        /**
         * Set symmetric reconnect jitter in the range {@code [0, 1)}.
         *
         * @param jitterFactor the jitter factor
         * @return this builder
         */
        public Builder jitterFactor(double jitterFactor) {
            this.jitterFactor = jitterFactor;
            return this;
        }

        /**
         * Build the policy.
         *
         * @return the policy
         * @throws IllegalArgumentException if a setting is out of range
         */
        public ReconnectPolicy build() {
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
