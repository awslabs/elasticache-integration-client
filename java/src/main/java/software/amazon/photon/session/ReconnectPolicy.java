package software.amazon.photon.session;

/**
 * Reconnect and circuit-breaker settings for a Photon session.
 */
public final class ReconnectPolicy {

    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 3;
    public static final long DEFAULT_BASE_DELAY_MS = 100;
    public static final long DEFAULT_MAX_DELAY_MS = 5_000;
    public static final int DEFAULT_CIRCUIT_FAILURE_THRESHOLD = 3;
    public static final long DEFAULT_CIRCUIT_OPEN_DURATION_MS = 30_000;

    private final int maxReconnectAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final int circuitFailureThreshold;
    private final long circuitOpenDurationMs;

    private ReconnectPolicy(Builder builder) {
        this.maxReconnectAttempts = builder.maxReconnectAttempts;
        this.baseDelayMs = builder.baseDelayMs;
        this.maxDelayMs = builder.maxDelayMs;
        this.circuitFailureThreshold = builder.circuitFailureThreshold;
        this.circuitOpenDurationMs = builder.circuitOpenDurationMs;
    }

    public static ReconnectPolicy defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public long getBaseDelayMs() {
        return baseDelayMs;
    }

    public long getMaxDelayMs() {
        return maxDelayMs;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public long getCircuitOpenDurationMs() {
        return circuitOpenDurationMs;
    }

    public static final class Builder {
        private int maxReconnectAttempts = DEFAULT_MAX_RECONNECT_ATTEMPTS;
        private long baseDelayMs = DEFAULT_BASE_DELAY_MS;
        private long maxDelayMs = DEFAULT_MAX_DELAY_MS;
        private int circuitFailureThreshold = DEFAULT_CIRCUIT_FAILURE_THRESHOLD;
        private long circuitOpenDurationMs = DEFAULT_CIRCUIT_OPEN_DURATION_MS;

        private Builder() {
        }

        public Builder maxReconnectAttempts(int maxReconnectAttempts) {
            this.maxReconnectAttempts = maxReconnectAttempts;
            return this;
        }

        public Builder baseDelayMs(long baseDelayMs) {
            this.baseDelayMs = baseDelayMs;
            return this;
        }

        public Builder maxDelayMs(long maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            return this;
        }

        public Builder circuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
            return this;
        }

        public Builder circuitOpenDurationMs(long circuitOpenDurationMs) {
            this.circuitOpenDurationMs = circuitOpenDurationMs;
            return this;
        }

        public ReconnectPolicy build() {
            if (maxReconnectAttempts < 0) {
                throw new IllegalArgumentException("maxReconnectAttempts must not be negative");
            }
            requirePositive("baseDelayMs", baseDelayMs);
            requirePositive("maxDelayMs", maxDelayMs);
            if (maxDelayMs < baseDelayMs) {
                throw new IllegalArgumentException("maxDelayMs must be at least baseDelayMs");
            }
            requirePositive("circuitFailureThreshold", circuitFailureThreshold);
            requirePositive("circuitOpenDurationMs", circuitOpenDurationMs);
            return new ReconnectPolicy(this);
        }

        private static void requirePositive(String name, long value) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
        }
    }
}
