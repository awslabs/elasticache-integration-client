package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Factory for creating AWS SDK clients wired up with Cache.
 *
 * <p>This provides a one-liner API that creates an SDK client, connects to Cache, negotiates RESP3
 * and authenticates, performs the config exchange, and installs the interceptor and Cache-first
 * HTTP transport with transparent origin fallback.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ElastiCacheIntegrationsClient.Result<DynamoDbClient> result = ElastiCacheIntegrationsClient.builder()
 *     .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
 *     .authProvider(iamAuthProvider)
 *     .origin(DynamoDbClient.builder())
 *     .build();
 *
 * try (result) {
 *     ListTablesResponse response = result.client().listTables();
 * }
 * }</pre>
 *
 * <p>The {@link Result} implements {@link Closeable} and closes both the Cache connection and the
 * SDK client. Use try-with-resources for automatic cleanup. Asynchronous SDK clients are created
 * with {@link Builder#buildAsync} and returned in an {@link AsyncResult}.
 *
 * <p>For more control over lifecycle, use {@link ElastiCacheIntegrationsHandle} directly.
 */
public final class ElastiCacheIntegrationsClient {

    private ElastiCacheIntegrationsClient() {
        // Factory class
    }

    /** Create a new builder for configuring a Cache-enabled SDK client. */
    public static Builder builder() {
        return new Builder();
    }

    private abstract static class ManagedResult<C extends SdkClient> implements Closeable {
        private final C client;

        private ManagedResult(C client) {
            this.client = client;
        }

        public C client() {
            return client;
        }

        protected abstract void closeHandle();

        @Override
        public final void close() {
            try {
                closeHandle();
            } finally {
                client.close();
            }
        }
    }

    /**
     * Result of building a Cache-enabled SDK client.
     *
     * <p>Contains the SDK client and Cache handle. Implements {@link Closeable} to close both
     * resources together. Use try-with-resources:
     *
     * <pre>{@code
     * try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result =
     *         ElastiCacheIntegrationsClient.builder()...origin(...).build()) {
     *     result.client().listTables();
     * }
     * }</pre>
     *
     * @param <C> the SDK client type
     */
    public static final class Result<C extends SdkClient> extends ManagedResult<C> {
        private final ElastiCacheIntegrationsHandle handle;

        Result(C client, ElastiCacheIntegrationsHandle handle) {
            super(client);
            this.handle = handle;
        }

        /** Get the Cache handle for direct access to session state. */
        public ElastiCacheIntegrationsHandle handle() {
            return handle;
        }

        @Override
        protected void closeHandle() {
            handle.close();
        }
    }

    /**
     * Result of building an asynchronous Cache-enabled SDK client.
     *
     * @param <C> the asynchronous SDK client type
     */
    public static final class AsyncResult<C extends SdkClient> extends ManagedResult<C> {
        private final ElastiCacheIntegrationsAsyncHandle handle;

        AsyncResult(C client, ElastiCacheIntegrationsAsyncHandle handle) {
            super(client);
            this.handle = handle;
        }

        public ElastiCacheIntegrationsAsyncHandle handle() {
            return handle;
        }

        @Override
        protected void closeHandle() {
            handle.close();
        }
    }

    /** Builder for creating Cache-enabled SDK clients. */
    public static final class Builder {
        private String endpoint;
        private String host;
        private int port = 6379;
        private SSLContext sslContext;
        private Supplier<AuthCredentials> authProvider;
        private IamAuthTokenProvider iamAuthProvider;
        private Map<String, Object> overrides;
        private int connectTimeoutMs = 5000;
        private int initializationTimeoutMs = 5000;
        private int requestTimeoutMs = 5000;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
        private long stalenessDurationSeconds =
                ElastiCacheIntegrationsSession.DEFAULT_STALENESS_DURATION_SECONDS;
        private SdkHttpClient originHttpClient;
        private SdkAsyncHttpClient originAsyncHttpClient;
        private Executor asyncExecutor;
        private int asyncMaxBufferedRequestBytes =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int asyncMaxConcurrentBodyCollectors =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;

        private Builder() {}

        /**
         * Set the cache endpoint as host:port (e.g., "cache.example.com:6379").
         *
         * <p>Supports IPv6 addresses in bracket notation (e.g., "[::1]:6379").
         *
         * <p>This is mutually exclusive with {@link #host(String)} and {@link #port(int)}.
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Set the cache host.
         *
         * <p>Use with {@link #port(int)}, or use {@link #endpoint(String)} instead.
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the cache port (default: 6379).
         *
         * <p>Use with {@link #host(String)}, or use {@link #endpoint(String)} instead.
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Set the SSL context (optional, uses default TLS if not specified). */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the auth provider (required).
         *
         * <p>For IAM authentication with automatic background refresh, use {@code IamAuthTokenProvider}
         * directly:
         *
         * <pre>{@code
         * IamAuthTokenProvider iamAuth = IamAuthTokenProvider.builder()
         *     .cacheName("my-cache")
         *     .region(Region.US_EAST_1)
         *     .build();
         * .authProvider(iamAuth)
         * }</pre>
         *
         * <p>Cache creates and closes an auto-refresh manager for an {@code IamAuthTokenProvider},
         * including one held through a {@code Supplier} reference. Other providers remain caller-owned.
         */
        public Builder authProvider(Supplier<AuthCredentials> authProvider) {
            if (authProvider instanceof IamAuthTokenProvider) {
                return authProvider((IamAuthTokenProvider) authProvider);
            }
            this.authProvider = authProvider;
            this.iamAuthProvider = null;
            return this;
        }

        /** Set an IAM provider whose auto-refresh lifecycle Cache owns. */
        public Builder authProvider(IamAuthTokenProvider authProvider) {
            this.iamAuthProvider = authProvider;
            this.authProvider = null;
            return this;
        }

        /** Set static auth credentials (convenience for non-IAM auth). */
        public Builder authCredentials(String userId, String authToken) {
            this.authProvider = () -> new AuthCredentials(userId, authToken);
            this.iamAuthProvider = null;
            return this;
        }

        /**
         * Set the cache policy overrides sent in the {@code aws.config} handshake (optional).
         *
         * <p>{@code cached_tables} and {@code cached_operations} are allowlists — each empty or absent
         * means everything — so keeping a table out of the cache means naming the tables that should be
         * cached rather than the one that should not. The lifetimes ({@code item_ttl}, {@code
         * item_negative_ttl}, {@code query_ttl}, {@code scan_ttl}) and {@code item_write_behavior}
         * apply connection-wide, or per table under {@code table_overrides}.
         *
         * <p>The map is sent as given. A field name the proxy does not recognise is ignored, so a typo
         * or a stale key is dropped without an error.
         */
        public Builder overrides(Map<String, Object> overrides) {
            this.overrides = overrides;
            return this;
        }

        /** Set connect timeout in milliseconds (default: 5000). */
        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /** Set the HELLO/authentication and configuration timeout in milliseconds (default: 5000). */
        public Builder initializationTimeoutMs(int initializationTimeoutMs) {
            this.initializationTimeoutMs = initializationTimeoutMs;
            return this;
        }

        /** Set the total timeout for each Cache request attempt in milliseconds (default: 5000). */
        public Builder requestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        /** Set reconnect and origin-fallback behavior for sync and async clients. */
        public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
            if (reconnectPolicy == null) {
                throw new NullPointerException("reconnectPolicy");
            }
            this.reconnectPolicy = reconnectPolicy;
            return this;
        }

        /**
         * Set how long, in seconds, the resolved config stays trusted while refreshes are failing. Past
         * it the connection is dropped so a new one re-resolves the config. Defaults to one hour; must
         * be a positive whole number of seconds.
         */
        public Builder stalenessDurationSeconds(long stalenessDurationSeconds) {
            this.stalenessDurationSeconds = stalenessDurationSeconds;
            return this;
        }

        /**
         * Set a caller-managed HTTP client for origin fallback.
         *
         * <p>Cache does not close a caller-provided client.
         */
        public Builder originHttpClient(SdkHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set a caller-managed asynchronous HTTP client for async origin fallback.
         *
         * <p>Cache does not close a caller-provided client.
         */
        public Builder originAsyncHttpClient(SdkAsyncHttpClient originAsyncHttpClient) {
            this.originAsyncHttpClient = originAsyncHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor used by the async transport for blocking Cache socket
         * requests.
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        /** Set the maximum async request body Cache buffers (default: 16 MiB). */
        public Builder asyncMaxBufferedRequestBytes(int asyncMaxBufferedRequestBytes) {
            this.asyncMaxBufferedRequestBytes = asyncMaxBufferedRequestBytes;
            return this;
        }

        /** Set the maximum async request bodies collected concurrently (default: 8). */
        public Builder asyncMaxConcurrentBodyCollectors(int asyncMaxConcurrentBodyCollectors) {
            this.asyncMaxConcurrentBodyCollectors = asyncMaxConcurrentBodyCollectors;
            return this;
        }

        /**
         * Set the origin SDK client builder for a Cache-enabled client.
         *
         * <p>Calling {@link OriginBuilder#build()} on the returned builder connects to Cache,
         * negotiates RESP3 and authenticates, performs the config exchange, installs the interceptor
         * and HTTP transport, and returns a {@link Result} containing the ready-to-use SDK client.
         *
         * <p>Example:
         *
         * <pre>{@code
         * try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result = ElastiCacheIntegrationsClient.builder()
         *         .endpoint("cache.example.com:6379")
         *         .authProvider(iamAuth)
         *         .origin(DynamoDbClient.builder())
         *         .build()) {
         *     result.client().listTables();
         * }
         * }</pre>
         *
         * @param sdkClientBuilder the SDK client builder (e.g., {@code DynamoDbClient.builder()})
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return a typed builder ready to build the Cache-enabled SDK client
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                OriginBuilder<B, C> origin(B sdkClientBuilder) {
            return origin(sdkClientBuilder, null);
        }

        /**
         * Set the origin SDK client builder and additional SDK configuration.
         *
         * <p>Example:
         *
         * <pre>{@code
         * try (ElastiCacheIntegrationsClient.Result<DynamoDbClient> result = ElastiCacheIntegrationsClient.builder()
         *         .endpoint("cache.example.com:6379")
         *         .authProvider(iamAuth)
         *         .origin(DynamoDbClient.builder(), b -> b.region(Region.US_EAST_1))
         *         .build()) {
         *     result.client().listTables();
         * }
         * }</pre>
         *
         * @param sdkClientBuilder the SDK client builder (e.g., {@code DynamoDbClient.builder()})
         * @param sdkConfigurer additional SDK client configuration (may be null)
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return a typed builder ready to build the Cache-enabled SDK client
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                OriginBuilder<B, C> origin(B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            if (sdkClientBuilder == null) {
                throw new NullPointerException("sdkClientBuilder");
            }
            return new OriginBuilder<>(this, sdkClientBuilder, sdkConfigurer);
        }

        private <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                Result<C> build(B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            ElastiCacheIntegrationsHandle handle = buildHandle();

            try {
                handle.open();
            } catch (RuntimeException initializationFailure) {
                handle.close();
                throw initializationFailure;
            }

            // Apply user's SDK configuration and build client - close handle on any failure
            C client;
            try {
                if (sdkConfigurer != null) {
                    sdkConfigurer.accept(sdkClientBuilder);
                }

                // Merge Cache interceptor into any existing override configuration
                // instead of replacing it (preserves user's retries, timeouts, metrics, etc.)
                ClientOverrideConfiguration mergedConfig =
                        mergeOverrideConfig(sdkClientBuilder, handle.getInterceptor());

                client =
                        sdkClientBuilder
                                .overrideConfiguration(mergedConfig)
                                .httpClient(handle.getHttpClient())
                                .build();
            } catch (Exception e) {
                try {
                    handle.close();
                } catch (Exception closeEx) {
                    // Preserve the original exception
                }
                throw e;
            }

            return new Result<>(client, handle);
        }

        /** Build an asynchronous SDK client with Cache enabled. */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                AsyncResult<C> buildAsync(B sdkClientBuilder) {
            return buildAsync(sdkClientBuilder, null);
        }

        /** Build an asynchronous SDK client with Cache enabled and additional SDK configuration. */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                AsyncResult<C> buildAsync(B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            ElastiCacheIntegrationsAsyncHandle handle = buildAsyncHandle();

            try {
                handle.open();
            } catch (RuntimeException initializationFailure) {
                handle.close();
                throw initializationFailure;
            }

            C client;
            try {
                if (sdkConfigurer != null) {
                    sdkConfigurer.accept(sdkClientBuilder);
                }
                ClientOverrideConfiguration mergedConfig =
                        mergeOverrideConfig(sdkClientBuilder, handle.getInterceptor());
                client =
                        sdkClientBuilder
                                .overrideConfiguration(mergedConfig)
                                .httpClient(handle.getHttpClient())
                                .build();
            } catch (Exception e) {
                try {
                    handle.close();
                } catch (Exception closeEx) {
                    // Preserve the original exception
                }
                throw e;
            }

            return new AsyncResult<>(client, handle);
        }

        private ElastiCacheIntegrationsHandle buildHandle() {
            EndpointParser.ParsedEndpoint resolvedEndpoint = resolveCacheEndpoint();

            ElastiCacheIntegrationsHandle.Builder handleBuilder =
                    ElastiCacheIntegrationsHandle.builder()
                            .host(resolvedEndpoint.getHost())
                            .port(resolvedEndpoint.getPort())
                            .sslContext(sslContext)
                            .overrides(overrides)
                            .connectTimeoutMs(connectTimeoutMs)
                            .initializationTimeoutMs(initializationTimeoutMs)
                            .requestTimeoutMs(requestTimeoutMs)
                            .reconnectPolicy(reconnectPolicy)
                            .stalenessDurationSeconds(stalenessDurationSeconds)
                            .originHttpClient(originHttpClient);
            if (iamAuthProvider != null) {
                handleBuilder.authProvider(iamAuthProvider);
            } else {
                handleBuilder.authProvider(authProvider);
            }
            return handleBuilder.build();
        }

        private ElastiCacheIntegrationsAsyncHandle buildAsyncHandle() {
            EndpointParser.ParsedEndpoint resolvedEndpoint = resolveCacheEndpoint();

            ElastiCacheIntegrationsAsyncHandle.Builder handleBuilder =
                    ElastiCacheIntegrationsAsyncHandle.builder()
                            .host(resolvedEndpoint.getHost())
                            .port(resolvedEndpoint.getPort())
                            .sslContext(sslContext)
                            .overrides(overrides)
                            .connectTimeoutMs(connectTimeoutMs)
                            .initializationTimeoutMs(initializationTimeoutMs)
                            .requestTimeoutMs(requestTimeoutMs)
                            .reconnectPolicy(reconnectPolicy)
                            .stalenessDurationSeconds(stalenessDurationSeconds)
                            .originHttpClient(originAsyncHttpClient)
                            .executor(asyncExecutor)
                            .maxBufferedRequestBytes(asyncMaxBufferedRequestBytes)
                            .maxConcurrentBodyCollectors(asyncMaxConcurrentBodyCollectors);
            if (iamAuthProvider != null) {
                handleBuilder.authProvider(iamAuthProvider);
            } else {
                handleBuilder.authProvider(authProvider);
            }
            return handleBuilder.build();
        }

        private EndpointParser.ParsedEndpoint resolveCacheEndpoint() {
            if (endpoint != null && host != null) {
                throw new ElastiCacheIntegrationsError("Cannot specify both endpoint and host");
            }
            if (endpoint != null) {
                return EndpointParser.parseEndpoint(endpoint);
            }
            if (host != null && !host.isEmpty()) {
                return new EndpointParser.ParsedEndpoint(host, port);
            }
            throw new ElastiCacheIntegrationsError("endpoint or host is required");
        }

        private <B extends AwsClientBuilder<B, C>, C extends SdkClient>
                ClientOverrideConfiguration mergeOverrideConfig(
                        B sdkClientBuilder, ExecutionInterceptor interceptor) {
            // Get existing config from the SDK builder (includes anything set via sdkConfigurer)
            // then add our Cache interceptor to it
            ClientOverrideConfiguration existingConfig = sdkClientBuilder.overrideConfiguration();
            ClientOverrideConfiguration.Builder configBuilder =
                    existingConfig != null
                            ? existingConfig.toBuilder()
                            : ClientOverrideConfiguration.builder();

            // Add the Cache interceptor (preserves any existing interceptors)
            configBuilder.addExecutionInterceptor(interceptor);

            return configBuilder.build();
        }
    }

    /**
     * Typed terminal builder for creating a Cache-enabled SDK client.
     *
     * @param <B> the SDK client builder type
     * @param <C> the SDK client type
     */
    public static final class OriginBuilder<
            B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient> {
        private final Builder cacheBuilder;
        private final B sdkClientBuilder;
        private final Consumer<B> sdkConfigurer;

        private OriginBuilder(Builder cacheBuilder, B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            this.cacheBuilder = cacheBuilder;
            this.sdkClientBuilder = sdkClientBuilder;
            this.sdkConfigurer = sdkConfigurer;
        }

        /**
         * Build the SDK client with Cache enabled.
         *
         * <p>This connects to Cache, negotiates RESP3 and authenticates, performs the config exchange,
         * installs the interceptor and HTTP transport, and returns a {@link Result} containing the
         * ready-to-use SDK client.
         *
         * @return a Result containing the configured SDK client and Cache handle
         * @throws ElastiCacheIntegrationsError if connection or authentication fails
         */
        public Result<C> build() {
            return cacheBuilder.build(sdkClientBuilder, sdkConfigurer);
        }
    }
}
