package software.amazon.photon.session;

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonError;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Factory for creating AWS SDK clients wired up with Photon.
 *
 * <p>This provides a one-liner API that creates an SDK client, connects to Photon,
 * authenticates, performs the config handshake, and installs the interceptor
 * and Photon-first HTTP transport with transparent origin fallback.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
 *     .endpoint("my-cache.serverless.use1.cache.amazonaws.com:6379")
 *     .authProvider(iamAuthProvider)
 *     .build(DynamoDbClient.builder());
 *
 * try (result) {
 *     ListTablesResponse response = result.client().listTables();
 * }
 * }</pre>
 *
 * <p>The {@link Result} implements {@link Closeable} and closes both the Photon
 * connection and the SDK client. Use try-with-resources for automatic cleanup.
 * Asynchronous SDK clients are created with {@link Builder#buildAsync} and
 * returned in an {@link AsyncResult}.
 *
 * <p>For more control over lifecycle, use {@link PhotonHandle} directly.
 */
public final class PhotonClient {

    private PhotonClient() {
        // Factory class
    }

    /**
     * Create a new builder for configuring a Photon-enabled SDK client.
     */
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
     * Result of building a Photon-enabled SDK client.
     *
     * <p>Contains the SDK client and Photon handle. Implements {@link Closeable}
     * to close both resources together. Use try-with-resources:
     *
     * <pre>{@code
     * try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()...build(...)) {
     *     result.client().listTables();
     * }
     * }</pre>
     *
     * @param <C> the SDK client type
     */
    public static final class Result<C extends SdkClient> extends ManagedResult<C> {
        private final PhotonHandle handle;

        Result(C client, PhotonHandle handle) {
            super(client);
            this.handle = handle;
        }

        /**
         * Get the Photon handle for direct access to session state.
         */
        public PhotonHandle handle() {
            return handle;
        }

        @Override
        protected void closeHandle() {
            handle.close();
        }
    }

    /**
     * Result of building an asynchronous Photon-enabled SDK client.
     *
     * @param <C> the asynchronous SDK client type
     */
    public static final class AsyncResult<C extends SdkClient> extends ManagedResult<C> {
        private final PhotonAsyncHandle handle;

        AsyncResult(C client, PhotonAsyncHandle handle) {
            super(client);
            this.handle = handle;
        }

        public PhotonAsyncHandle handle() {
            return handle;
        }

        @Override
        protected void closeHandle() {
            handle.close();
        }
    }

    /**
     * Builder for creating Photon-enabled SDK clients.
     */
    public static final class Builder {
        private String endpoint;
        private String host;
        private int port = 6379;
        private SSLContext sslContext;
        private Supplier<AuthCredentials> authProvider;
        private Map<String, Object> configOverrides;
        private int connectTimeoutMs = 5000;
        private int initializationTimeoutMs = 5000;
        private int requestTimeoutMs = 5000;
        private SdkHttpClient originHttpClient;
        private SdkAsyncHttpClient originAsyncHttpClient;
        private Executor asyncExecutor;
        private int asyncMaxBufferedRequestBytes =
                PhotonAsyncHandle.DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int asyncMaxConcurrentBodyCollectors =
                PhotonAsyncHandle.DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;

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

        /**
         * Set the SSL context (optional, uses default TLS if not specified).
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the auth provider (required).
         *
         * <p>For IAM authentication, use {@code IamAuthTokenProvider} directly:
         * <pre>{@code
         * IamAuthTokenProvider iamAuth = IamAuthTokenProvider.builder()
         *     .cacheName("my-cache")
         *     .region(Region.US_EAST_1)
         *     .build();
         * .authProvider(iamAuth)
         * }</pre>
         */
        public Builder authProvider(Supplier<AuthCredentials> authProvider) {
            this.authProvider = authProvider;
            return this;
        }

        /**
         * Set static auth credentials (convenience for non-IAM auth).
         */
        public Builder authCredentials(String userId, String authToken) {
            this.authProvider = () -> new AuthCredentials(userId, authToken);
            return this;
        }

        /**
         * Set config overrides sent in the photon.config handshake (optional).
         */
        public Builder configOverrides(Map<String, Object> configOverrides) {
            this.configOverrides = configOverrides;
            return this;
        }

        /**
         * Set connect timeout in milliseconds (default: 5000).
         */
        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * Set the AUTH and configuration handshake timeout in milliseconds (default: 5000).
         */
        public Builder initializationTimeoutMs(int initializationTimeoutMs) {
            this.initializationTimeoutMs = initializationTimeoutMs;
            return this;
        }

        /**
         * Set the total timeout for each Photon request attempt in milliseconds (default: 5000).
         */
        public Builder requestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        /**
         * Set a caller-managed HTTP client for origin fallback.
         *
         * <p>Photon does not close a caller-provided client.
         */
        public Builder originHttpClient(SdkHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set a caller-managed asynchronous HTTP client for async origin fallback.
         *
         * <p>Photon does not close a caller-provided client.
         */
        public Builder originAsyncHttpClient(SdkAsyncHttpClient originAsyncHttpClient) {
            this.originAsyncHttpClient = originAsyncHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor used by the async transport for blocking
         * Photon socket requests.
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        /**
         * Set the maximum async request body Photon buffers (default: 16 MiB).
         */
        public Builder asyncMaxBufferedRequestBytes(int asyncMaxBufferedRequestBytes) {
            this.asyncMaxBufferedRequestBytes = asyncMaxBufferedRequestBytes;
            return this;
        }

        /**
         * Set the maximum async request bodies collected concurrently (default: 8).
         */
        public Builder asyncMaxConcurrentBodyCollectors(int asyncMaxConcurrentBodyCollectors) {
            this.asyncMaxConcurrentBodyCollectors = asyncMaxConcurrentBodyCollectors;
            return this;
        }

        /**
         * Build the SDK client with Photon enabled.
         *
         * <p>This connects to Photon, authenticates, performs the config handshake,
         * installs the interceptor and HTTP transport, and returns a {@link Result}
         * containing the ready-to-use SDK client.
         *
         * <p>Example:
         * <pre>{@code
         * try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
         *         .endpoint("cache.example.com:6379")
         *         .authProvider(iamAuth)
         *         .build(DynamoDbClient.builder())) {
         *     result.client().listTables();
         * }
         * }</pre>
         *
         * @param sdkClientBuilder the SDK client builder (e.g., {@code DynamoDbClient.builder()})
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return a Result containing the configured SDK client and Photon handle
         * @throws PhotonError if connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                Result<C> build(B sdkClientBuilder) {
            return build(sdkClientBuilder, null);
        }

        /**
         * Build the SDK client with Photon enabled and additional SDK configuration.
         *
         * <p>Example:
         * <pre>{@code
         * try (PhotonClient.Result<DynamoDbClient> result = PhotonClient.builder()
         *         .endpoint("cache.example.com:6379")
         *         .authProvider(iamAuth)
         *         .build(DynamoDbClient.builder(), b -> b.region(Region.US_EAST_1))) {
         *     result.client().listTables();
         * }
         * }</pre>
         *
         * @param sdkClientBuilder the SDK client builder (e.g., {@code DynamoDbClient.builder()})
         * @param sdkConfigurer additional SDK client configuration (may be null)
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return a Result containing the configured SDK client and Photon handle
         * @throws PhotonError if connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient> Result<C> build(
                B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            
            PhotonHandle handle = buildHandle();
            
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

                // Merge Photon interceptor into any existing override configuration
                // instead of replacing it (preserves user's retries, timeouts, metrics, etc.)
                ClientOverrideConfiguration mergedConfig = mergeOverrideConfig(
                        sdkClientBuilder, handle.getInterceptor());

                client = sdkClientBuilder
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

        /**
         * Build an asynchronous SDK client with Photon enabled.
         */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                AsyncResult<C> buildAsync(B sdkClientBuilder) {
            return buildAsync(sdkClientBuilder, null);
        }

        /**
         * Build an asynchronous SDK client with Photon enabled and additional SDK configuration.
         */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                AsyncResult<C> buildAsync(B sdkClientBuilder, Consumer<B> sdkConfigurer) {
            PhotonAsyncHandle handle = buildAsyncHandle();

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
                ClientOverrideConfiguration mergedConfig = mergeOverrideConfig(
                        sdkClientBuilder, handle.getInterceptor());
                client = sdkClientBuilder
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

        private PhotonHandle buildHandle() {
            EndpointParser.ParsedEndpoint resolvedEndpoint = resolvePhotonEndpoint();

            return PhotonHandle.builder()
                    .host(resolvedEndpoint.getHost())
                    .port(resolvedEndpoint.getPort())
                    .sslContext(sslContext)
                    .authProvider(authProvider)
                    .configOverrides(configOverrides)
                    .connectTimeoutMs(connectTimeoutMs)
                    .initializationTimeoutMs(initializationTimeoutMs)
                    .requestTimeoutMs(requestTimeoutMs)
                    .originHttpClient(originHttpClient)
                    .build();
        }

        private PhotonAsyncHandle buildAsyncHandle() {
            EndpointParser.ParsedEndpoint resolvedEndpoint = resolvePhotonEndpoint();

            return PhotonAsyncHandle.builder()
                    .host(resolvedEndpoint.getHost())
                    .port(resolvedEndpoint.getPort())
                    .sslContext(sslContext)
                    .authProvider(authProvider)
                    .configOverrides(configOverrides)
                    .connectTimeoutMs(connectTimeoutMs)
                    .initializationTimeoutMs(initializationTimeoutMs)
                    .requestTimeoutMs(requestTimeoutMs)
                    .originHttpClient(originAsyncHttpClient)
                    .executor(asyncExecutor)
                    .maxBufferedRequestBytes(asyncMaxBufferedRequestBytes)
                    .maxConcurrentBodyCollectors(asyncMaxConcurrentBodyCollectors)
                    .build();
        }

        private EndpointParser.ParsedEndpoint resolvePhotonEndpoint() {
            if (endpoint != null && host != null) {
                throw new PhotonError("Cannot specify both endpoint and host");
            }
            if (endpoint != null) {
                return EndpointParser.parseEndpoint(endpoint);
            }
            if (host != null && !host.isEmpty()) {
                return new EndpointParser.ParsedEndpoint(host, port);
            }
            throw new PhotonError("endpoint or host is required");
        }

        private <B extends AwsClientBuilder<B, C>, C extends SdkClient> ClientOverrideConfiguration mergeOverrideConfig(
                B sdkClientBuilder, ExecutionInterceptor interceptor) {
            // Get existing config from the SDK builder (includes anything set via sdkConfigurer)
            // then add our Photon interceptor to it
            ClientOverrideConfiguration existingConfig = sdkClientBuilder.overrideConfiguration();
            ClientOverrideConfiguration.Builder configBuilder = existingConfig != null
                    ? existingConfig.toBuilder()
                    : ClientOverrideConfiguration.builder();
            
            // Add the Photon interceptor (preserves any existing interceptors)
            configBuilder.addExecutionInterceptor(interceptor);
            
            return configBuilder.build();
        }
    }
}
