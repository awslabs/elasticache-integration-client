package software.amazon.elasticache.integrations.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final long fallbackDurationSeconds;
    private final Map<String, Object> cachePolicyOverrides;
    private final long cachePolicyTimeoutSeconds;
    private final Duration iamTokenRefreshDelay;

    private ElastiCacheIntegrationsClientConfiguration(Builder builder) {
        this.cacheRequestTimeoutMs = builder.cacheRequestTimeoutMs;
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
        private long fallbackDurationSeconds = INDEFINITE_FALLBACK;
        private Map<String, Object> cachePolicyOverrides;
        private long cachePolicyTimeoutSeconds = DEFAULT_CACHE_POLICY_TIMEOUT_SECONDS;
        private Duration iamTokenRefreshDelay;

        private Builder() {}

        /**
         * Set how long to wait for each cache request's reply, in milliseconds (default: 5000). Must be
         * positive.
         */
        public Builder cacheRequestTimeoutMs(long cacheRequestTimeoutMs) {
            this.cacheRequestTimeoutMs = cacheRequestTimeoutMs;
            return this;
        }

        /**
         * Set how long, in seconds, requests may go directly to the origin service while there is no
         * cache connection. A negative value (the default) means indefinitely, so a cache outage never
         * becomes an application outage. Zero means never: the first request after a connection is lost
         * fails with {@code ElastiCacheIntegrationsCacheUnavailable}.
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
         * error.
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
         * Set how long after a token is issued the IAM toolkit signs a replacement in the background,
         * while callers keep using the current token. Must be greater than 0 and less than 880 seconds.
         * When null (the default), the toolkit default of 300 seconds applies.
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
            if (cacheRequestTimeoutMs <= 0) {
                throw new ElastiCacheIntegrationsConfigError(
                        "cacheRequestTimeoutMs must be a positive number of milliseconds");
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
            this.cachePolicyOverrides =
                    cachePolicyOverrides == null ? null : deepCopyMap(cachePolicyOverrides);
            return new ElastiCacheIntegrationsClientConfiguration(this);
        }

        private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                copy.put(entry.getKey(), deepCopy(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }

        @SuppressWarnings("unchecked")
        private static Object deepCopy(Object value) {
            if (value instanceof Map) {
                return deepCopyMap((Map<String, Object>) value);
            }
            if (value instanceof List) {
                List<Object> copy = new ArrayList<>();
                for (Object element : (List<Object>) value) {
                    copy.add(deepCopy(element));
                }
                return Collections.unmodifiableList(copy);
            }
            return value;
        }
    }
}
