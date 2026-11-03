package software.amazon.elasticache.integrations.session;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;

/**
 * Optional ElastiCache Integrations settings. Every option has a default, so set only what you
 * change.
 *
 * <p>The instance is immutable. {@link Builder#build()} throws {@link
 * ElastiCacheIntegrationsConfigError} for a value that is not usable.
 */
public final class ElastiCacheIntegrationsClientConfiguration {

    /** Default for {@link Builder#cacheRequestTimeoutMs(long)}: 5 seconds. */
    public static final long DEFAULT_CACHE_REQUEST_TIMEOUT_MS = 5_000;

    /** Default for {@link Builder#connectionTimeoutMs(long)}: 2 seconds. */
    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 2_000;

    /** Value of {@link Builder#fallbackDurationSeconds(long)} that allows fallback indefinitely. */
    public static final long INDEFINITE_FALLBACK = -1;

    /** Default for {@link Builder#cachePolicyTimeoutSeconds(long)}: one hour. */
    public static final long DEFAULT_CACHE_POLICY_TIMEOUT_SECONDS =
            ElastiCacheIntegrationsSession.DEFAULT_STALENESS_DURATION_SECONDS;

    /**
     * Upper bound (exclusive) of {@link Builder#iamTokenRefreshDelay(Duration)}, which keeps the
     * toolkit's 20-second safety margin on the 15-minute token.
     */
    static final long MAX_IAM_TOKEN_REFRESH_DELAY_SECONDS = 880;

    private final long cacheRequestTimeoutMs;
    private final long connectionTimeoutMs;
    private final long fallbackDurationSeconds;
    private final Map<String, Object> cachePolicyOverrides;
    private final long cachePolicyTimeoutSeconds;
    private final Duration iamTokenRefreshDelay;

    private ElastiCacheIntegrationsClientConfiguration(Builder builder) {
        this.cacheRequestTimeoutMs = builder.cacheRequestTimeoutMs;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.fallbackDurationSeconds = builder.fallbackDurationSeconds;
        this.cachePolicyOverrides = builder.cachePolicyOverrides;
        this.cachePolicyTimeoutSeconds = builder.cachePolicyTimeoutSeconds;
        this.iamTokenRefreshDelay = builder.iamTokenRefreshDelay;
    }

    /** Return a configuration with every option at its default. */
    public static ElastiCacheIntegrationsClientConfiguration defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Return how long to wait for each cache request's reply, in milliseconds. */
    public long getCacheRequestTimeoutMs() {
        return cacheRequestTimeoutMs;
    }

    /**
     * Return how long to try to connect at startup, and how long a request waits for reconnection
     * after a connection is lost, in milliseconds.
     */
    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    /**
     * Return how long requests may go to the origin service while there is no cache connection, in
     * seconds. A negative value means indefinitely and zero means never.
     */
    public long getFallbackDurationSeconds() {
        return fallbackDurationSeconds;
    }

    /** Return the cache policy overrides, or null when none were set. The map is unmodifiable. */
    public Map<String, Object> getCachePolicyOverrides() {
        return cachePolicyOverrides;
    }

    /** Return how long a cache policy may be used without confirmation, in seconds. */
    public long getCachePolicyTimeoutSeconds() {
        return cachePolicyTimeoutSeconds;
    }

    /** Return the IAM token refresh delay, or null for the toolkit default. */
    public Duration getIamTokenRefreshDelay() {
        return iamTokenRefreshDelay;
    }

    /** Builder for {@link ElastiCacheIntegrationsClientConfiguration}. */
    public static final class Builder {
        private long cacheRequestTimeoutMs = DEFAULT_CACHE_REQUEST_TIMEOUT_MS;
        private long connectionTimeoutMs = DEFAULT_CONNECTION_TIMEOUT_MS;
        private long fallbackDurationSeconds = INDEFINITE_FALLBACK;
        private Map<String, Object> cachePolicyOverrides;
        private long cachePolicyTimeoutSeconds = DEFAULT_CACHE_POLICY_TIMEOUT_SECONDS;
        private Duration iamTokenRefreshDelay;

        private Builder() {}

        /**
         * Set how long to wait for each cache request's reply, in milliseconds (default: 5000). Must be
         * positive and at most {@link Integer#MAX_VALUE}.
         */
        public Builder cacheRequestTimeoutMs(long cacheRequestTimeoutMs) {
            this.cacheRequestTimeoutMs = cacheRequestTimeoutMs;
            return this;
        }

        /**
         * Set how long, in milliseconds, ElastiCache Integrations tries to connect at startup, and how
         * long a request waits for reconnection after a connection is lost (default: 2000). Must be
         * positive.
         */
        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        /**
         * Set how long, in seconds, routed requests may use the origin service while there is no cache
         * connection. The window is shared by requests during one outage. A later outage gets a new
         * window after recovery.
         *
         * <ul>
         *   <li>A negative value (the default): allow fallback for the rest of the outage.
         *   <li>{@code N > 0}: allow fallback for N seconds. Later requests fail with {@code
         *       ElastiCacheIntegrationsCacheUnavailable}.
         *   <li>{@code 0}: disable fallback. Requests fail as soon as the connection is lost.
         * </ul>
         */
        public Builder fallbackDurationSeconds(long fallbackDurationSeconds) {
            this.fallbackDurationSeconds = fallbackDurationSeconds;
            return this;
        }

        /**
         * Set the settings that change what ElastiCache Integrations caches, sent in the {@code
         * aws.config} handshake (optional). The map is deep-copied by {@link #build()}.
         *
         * <p>{@code cached_tables} and {@code cached_operations} are allowlists. Each empty or absent
         * means everything, so keeping a table out of the cache means naming the tables that should be
         * cached rather than the one that should not. The lifetimes ({@code item_ttl}, {@code
         * item_negative_ttl}, {@code query_ttl}, {@code scan_ttl}) and {@code item_write_behavior}
         * apply connection-wide, or per table under {@code table_overrides}.
         *
         * <p>The cache ignores a field name it does not recognise, so a typo is dropped without an
         * error. Values must be JSON values: null, a string, a boolean, a finite number, a collection
         * or object array, or a map with string keys. A number must be a boxed primitive, {@link
         * BigInteger}, or {@link BigDecimal}. A collection or array is copied as a list, and a value
         * must not contain itself.
         */
        public Builder cachePolicyOverrides(Map<String, Object> cachePolicyOverrides) {
            this.cachePolicyOverrides = cachePolicyOverrides;
            return this;
        }

        /**
         * Set how long, in seconds, a cache policy may be used without confirmation from the cache
         * (default: 3600). After this time the next failed confirmation drops the connection so a
         * replacement can fetch a current policy. Must be positive.
         */
        public Builder cachePolicyTimeoutSeconds(long cachePolicyTimeoutSeconds) {
            this.cachePolicyTimeoutSeconds = cachePolicyTimeoutSeconds;
            return this;
        }

        /**
         * Set how long after a token is issued before the IAM toolkit signs a replacement in the
         * background, while callers keep using the current token. Must be greater than 0 and less than
         * 880 seconds. When null (the default), the toolkit default of 300 seconds is used.
         */
        public Builder iamTokenRefreshDelay(Duration iamTokenRefreshDelay) {
            this.iamTokenRefreshDelay = iamTokenRefreshDelay;
            return this;
        }

        /**
         * Build the configuration.
         *
         * @throws ElastiCacheIntegrationsConfigError if a value is not usable
         */
        public ElastiCacheIntegrationsClientConfiguration build() {
            if (cacheRequestTimeoutMs <= 0 || cacheRequestTimeoutMs > Integer.MAX_VALUE) {
                throw new ElastiCacheIntegrationsConfigError(
                        "cacheRequestTimeoutMs must be a positive number of milliseconds, at most "
                                + Integer.MAX_VALUE);
            }
            if (connectionTimeoutMs <= 0) {
                throw new ElastiCacheIntegrationsConfigError(
                        "connectionTimeoutMs must be a positive number of milliseconds");
            }
            if (cachePolicyTimeoutSeconds <= 0
                    || cachePolicyTimeoutSeconds > TimeUnit.NANOSECONDS.toSeconds(Long.MAX_VALUE)) {
                throw new ElastiCacheIntegrationsConfigError(
                        "cachePolicyTimeoutSeconds must be a positive whole number of seconds");
            }
            if (iamTokenRefreshDelay != null
                    && (iamTokenRefreshDelay.isNegative()
                            || iamTokenRefreshDelay.isZero()
                            || iamTokenRefreshDelay.compareTo(
                                            Duration.ofSeconds(MAX_IAM_TOKEN_REFRESH_DELAY_SECONDS))
                                    >= 0)) {
                throw new ElastiCacheIntegrationsConfigError(
                        "iamTokenRefreshDelay must be a Duration greater than 0 and less than "
                                + MAX_IAM_TOKEN_REFRESH_DELAY_SECONDS
                                + " seconds, or null to use the toolkit default");
            }
            // Copied and checked here so a value the aws.config handshake cannot send fails before
            // connecting, and a later change to the caller's map has no effect.
            this.cachePolicyOverrides =
                    cachePolicyOverrides == null
                            ? null
                            : deepCopyMap(
                                    cachePolicyOverrides,
                                    "cachePolicyOverrides",
                                    Collections.newSetFromMap(new IdentityHashMap<>()));
            return new ElastiCacheIntegrationsClientConfiguration(this);
        }

        // `enclosing` holds the maps and collections on the path to `source`, so a value that
        // contains itself fails here instead of recursing until StackOverflowError. The same
        // value in two separate places is allowed.
        private static Map<String, Object> deepCopyMap(
                Map<?, ?> source, String path, Set<Object> enclosing) {
            Map<String, Object> copy = new LinkedHashMap<>();
            enclosing.add(source);
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String)) {
                    throw new ElastiCacheIntegrationsConfigError(
                            path + " must have string keys, got " + typeName(key));
                }
                copy.put((String) key, deepCopy(entry.getValue(), path + "." + key, enclosing));
            }
            enclosing.remove(source);
            return Collections.unmodifiableMap(copy);
        }

        private static Object deepCopy(Object value, String path, Set<Object> enclosing) {
            if (enclosing.contains(value)) {
                throw new ElastiCacheIntegrationsConfigError(path + " must not contain itself");
            }
            if (value instanceof Map) {
                return deepCopyMap((Map<?, ?>) value, path, enclosing);
            }
            if (value instanceof Collection || value instanceof Object[]) {
                // A Set or an array is sent as a JSON array, so it is copied as a List.
                Collection<?> elements =
                        value instanceof Collection ? (Collection<?>) value : Arrays.asList((Object[]) value);
                List<Object> copy = new ArrayList<>();
                enclosing.add(value);
                int index = 0;
                for (Object element : elements) {
                    copy.add(deepCopy(element, path + "[" + index++ + "]", enclosing));
                }
                enclosing.remove(value);
                return Collections.unmodifiableList(copy);
            }
            if ((value instanceof Double && !Double.isFinite((Double) value))
                    || (value instanceof Float && !Float.isFinite((Float) value))) {
                throw new ElastiCacheIntegrationsConfigError(
                        path + " must be a finite number, got " + value);
            }
            // Only immutable Number types are kept, so a mutable one such as AtomicLong cannot
            // change the configuration after build().
            if (value == null
                    || value instanceof String
                    || value instanceof Boolean
                    || value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Float
                    || value instanceof Double
                    || value instanceof BigInteger
                    || value instanceof BigDecimal) {
                return value;
            }
            throw new ElastiCacheIntegrationsConfigError(
                    path + " must be JSON-serializable, got " + typeName(value));
        }

        private static String typeName(Object value) {
            return value == null ? "null" : value.getClass().getName();
        }
    }
}
