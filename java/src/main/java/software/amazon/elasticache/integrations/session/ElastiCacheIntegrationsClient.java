package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.awscore.AwsServiceClientConfiguration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.SdkServiceClientConfiguration;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkSyncClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.identity.spi.AwsSessionCredentialsIdentity;
import software.amazon.awssdk.identity.spi.IdentityProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.utils.AttributeMap;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Installs ElastiCache Integrations into an application's AWS SDK client builder.
 *
 * <p>The application builds its SDK client as it always does, with its own region, credentials,
 * override configuration and everything else, and hands the builder to {@link Builder#install} or
 * {@link Builder#installAsync}. The returned client has the same API and serves eligible requests
 * from the cache. The cache is authenticated with the region and credentials the client resolved.
 *
 * <pre>{@code
 * DynamoDbClient ddb = ElastiCacheIntegrationsClient.builder()
 *     .endpoint("my-cache:6379")
 *     .cacheName("my-cache")
 *     .install(DynamoDbClient.builder().region(Region.US_EAST_1));
 *
 * ddb.getItem(...);  // unchanged
 * ddb.close();       // disconnects the cache, then closes the client as usual
 * }</pre>
 *
 * <p>The older {@code origin(...)} and {@code buildAsync(...)} builder methods, and the options
 * only they read, are still here while callers move to {@code install}. They will be removed.
 */
public final class ElastiCacheIntegrationsClient {

    private static final String NO_REGION =
            "The AWS SDK client has no region, so the cache's IAM token cannot be signed. "
                    + "Create the client with a region.";
    private static final String NO_CREDENTIALS =
            "This AWS SDK client carries no credentials, so the cache cannot be authenticated. "
                    + "A client built with AnonymousCredentialsProvider is not supported.";

    private ElastiCacheIntegrationsClient() {
        // Factory class
    }

    /**
     * Create a new builder for installing the cache into an SDK client builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
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
    public static final class Result<C extends SdkClient> implements Closeable {
        private final C client;
        private final ElastiCacheIntegrationsHandle handle;

        Result(C client, ElastiCacheIntegrationsHandle handle) {
            this.client = client;
            this.handle = handle;
        }

        /**
         * Get the SDK client with Cache installed.
         *
         * @return the SDK client
         */
        public C client() {
            return client;
        }

        /**
         * Get the Cache handle for direct access to session state.
         *
         * @return the Cache handle
         */
        public ElastiCacheIntegrationsHandle handle() {
            return handle;
        }

        @Override
        public void close() {
            try {
                handle.close();
            } finally {
                client.close();
            }
        }
    }

    /**
     * Result of building an asynchronous Cache-enabled SDK client.
     *
     * @param <C> the asynchronous SDK client type
     */
    public static final class AsyncResult<C extends SdkClient> implements Closeable {
        private final C client;
        private final ElastiCacheIntegrationsAsyncHandle handle;

        AsyncResult(C client, ElastiCacheIntegrationsAsyncHandle handle) {
            this.client = client;
            this.handle = handle;
        }

        /**
         * Get the asynchronous SDK client with Cache installed.
         *
         * @return the asynchronous SDK client
         */
        public C client() {
            return client;
        }

        /**
         * Get the Cache handle for direct access to session state.
         *
         * @return the Cache handle
         */
        public ElastiCacheIntegrationsAsyncHandle handle() {
            return handle;
        }

        @Override
        public void close() {
            try {
                handle.close();
            } finally {
                client.close();
            }
        }
    }

    /** Builder for the cache connection options and the install step. */
    public static final class Builder {
        private String endpoint;
        private String cacheName;
        private String userId;
        private boolean verify = true;
        private Path caCerts;
        private ElastiCacheIntegrationsClientConfiguration clientConfiguration =
                ElastiCacheIntegrationsClientConfiguration.defaults();
        private SdkHttpClient originHttpClient;
        private SdkAsyncHttpClient originAsyncHttpClient;
        private Executor asyncExecutor;
        private int asyncMaxBufferedRequestBytes =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int asyncMaxConcurrentBodyCollectors =
                ElastiCacheIntegrationsAsyncHandle.DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;
        private Consumer<ElastiCacheIntegrationsHandle> handleObserver;
        private Consumer<ElastiCacheIntegrationsAsyncHandle> asyncHandleObserver;
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

        private Builder() {}

        /**
         * Set the cache endpoint as host:port (required), for example {@code "cache.example.com:6379"}.
         *
         * <p>Supports IPv6 addresses in bracket notation, for example {@code "[::1]:6379"}.
         *
         * <p>This is mutually exclusive with {@link #host(String)} and {@link #port(int)}.
         *
         * @param endpoint the cache endpoint as {@code host:port}
         * @return this builder
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /** Set the ElastiCache Serverless cache name (required). */
        public Builder cacheName(String cacheName) {
            this.cacheName = cacheName;
            return this;
        }

        /** Set the ElastiCache IAM user id (default: {@code default.iam-user}). */
        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        /**
         * Set whether to verify the cache's TLS certificate and host name (default: true). False is for
         * self-signed test rigs only.
         */
        public Builder verify(boolean verify) {
            this.verify = verify;
            return this;
        }

        /**
         * Set a PEM CA bundle file to verify the cache's certificate against, instead of the system
         * trust store (default: the system trust store). The host name is still checked.
         */
        public Builder caCerts(Path caCerts) {
            this.caCerts = caCerts;
            return this;
        }

        /** Set the cache behavior options. Null means all defaults, which is also the default. */
        public Builder clientConfiguration(
                ElastiCacheIntegrationsClientConfiguration clientConfiguration) {
            this.clientConfiguration =
                    clientConfiguration == null
                            ? ElastiCacheIntegrationsClientConfiguration.defaults()
                            : clientConfiguration;
            return this;
        }

        /**
         * Set the HTTP client that sends requests to the origin service when the cache does not serve
         * them.
         *
         * <p>Install replaces the transport of the SDK builder, and the SDK has no getter to read back
         * one the application already set, so pass that client here. The SDK client does not close it.
         * When unset, install creates the SDK Apache HTTP client and closes it with the SDK client.
         *
         * @param originHttpClient the HTTP client for origin requests
         * @return this builder
         */
        public Builder originHttpClient(SdkHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set the asynchronous HTTP client for origin requests, as {@link #originHttpClient} does for
         * synchronous clients. When unset, install creates the SDK Netty HTTP client.
         *
         * @param originAsyncHttpClient the asynchronous HTTP client for origin requests
         * @return this builder
         */
        public Builder originAsyncHttpClient(SdkAsyncHttpClient originAsyncHttpClient) {
            this.originAsyncHttpClient = originAsyncHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor the asynchronous transport uses for blocking cache socket
         * requests. The SDK client does not shut it down.
         *
         * @param asyncExecutor the executor for blocking cache requests
         * @return this builder
         */
        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
            return this;
        }

        /**
         * Set the maximum asynchronous request body the cache buffers (default: 16 MiB).
         *
         * @param asyncMaxBufferedRequestBytes the body size limit in bytes. A larger body goes to the
         *     origin.
         * @return this builder
         */
        public Builder asyncMaxBufferedRequestBytes(int asyncMaxBufferedRequestBytes) {
            this.asyncMaxBufferedRequestBytes = asyncMaxBufferedRequestBytes;
            return this;
        }

        /**
         * Set the maximum asynchronous request bodies collected concurrently (default: 8).
         *
         * @param asyncMaxConcurrentBodyCollectors the limit on bodies collected at once
         * @return this builder
         */
        public Builder asyncMaxConcurrentBodyCollectors(int asyncMaxConcurrentBodyCollectors) {
            this.asyncMaxConcurrentBodyCollectors = asyncMaxConcurrentBodyCollectors;
            return this;
        }

        /** For testing: receives each synchronous handle as soon as it is created. */
        Builder handleObserver(Consumer<ElastiCacheIntegrationsHandle> handleObserver) {
            this.handleObserver = handleObserver;
            return this;
        }

        /** For testing: receives each asynchronous handle as soon as it is created. */
        Builder asyncHandleObserver(Consumer<ElastiCacheIntegrationsAsyncHandle> asyncHandleObserver) {
            this.asyncHandleObserver = asyncHandleObserver;
            return this;
        }

        /**
         * Build the application's synchronous SDK client with the cache installed.
         *
         * <p>The builder keeps its own configuration. Install adds the cache interceptor to a copy of
         * its override configuration and replaces its HTTP transport. It returns after the cache
         * session is open: connected, authenticated with {@code HELLO 3 AUTH}, and configured with
         * {@code aws.config}. Closing the returned client disconnects the cache first.
         *
         * <p>A builder that already has an HTTP client fails, because the SDK rejects a builder that
         * has both an HTTP client and an HTTP client builder. Pass that client to {@link
         * #originHttpClient} and remove it from the builder.
         *
         * @param sdkClientBuilder the application's client builder, for example {@code
         *     DynamoDbClient.builder().region(Region.US_EAST_1)}
         * @return the built client
         * @throws ElastiCacheIntegrationsConfigError if an option or the client cannot be used
         * @throws ElastiCacheIntegrationsError if the cache connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkSyncClientBuilder<B, C>, C extends SdkClient>
                C install(B sdkClientBuilder) {
            requireBuilder(sdkClientBuilder);
            EndpointParser.ParsedEndpoint cacheEndpoint = validateOptions();
            SSLContext sslContext = CacheTls.create(verify, caCerts);

            ManagedIamAuthProvider auth = new ManagedIamAuthProvider();
            ElastiCacheIntegrationsHandle handle;
            try {
                handle =
                        ElastiCacheIntegrationsHandle.builder()
                                .host(cacheEndpoint.getHost())
                                .port(cacheEndpoint.getPort())
                                .sslContext(sslContext)
                                .managedAuthProvider(auth)
                                .overrides(clientConfiguration.getCachePolicyOverrides())
                                .requestTimeoutMs(installRequestTimeoutMs())
                                .reconnectPolicy(installReconnectPolicy())
                                .stalenessDurationSeconds(clientConfiguration.getCachePolicyTimeoutSeconds())
                                .originHttpClient(originHttpClient)
                                .build();
            } catch (RuntimeException | Error failure) {
                auth.close();
                throw failure;
            }
            if (handleObserver != null) {
                handleObserver.accept(handle);
            }

            ClientOverrideConfiguration original = sdkClientBuilder.overrideConfiguration();
            C client;
            try {
                client =
                        sdkClientBuilder
                                .overrideConfiguration(withInterceptor(original, handle.getInterceptor()))
                                .httpClientBuilder(new PrebuiltHttpClient(handle.getHttpClient()))
                                .build();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(handle, failure);
                ElastiCacheIntegrationsConfigError translated =
                        translateBuildFailure(failure, "originHttpClient");
                if (translated != null) {
                    throw translated;
                }
                throw failure;
            } finally {
                // The built client holds the cache; the application's builder goes back to how it was,
                // so it can build or install another client.
                sdkClientBuilder
                        .overrideConfiguration(original)
                        .httpClientBuilder((SdkHttpClient.Builder<?>) null);
            }

            try {
                bindAndOpen(client, auth, handle::open);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(client, failure);
                throw failure;
            }
            return client;
        }

        /**
         * Build the application's asynchronous SDK client with the cache installed.
         *
         * <p>It does for an asynchronous builder, for example {@code DynamoDbAsyncClient.builder()},
         * what {@link #install} does for a synchronous one. A builder that already has an HTTP client
         * fails; pass that client to {@link #originAsyncHttpClient}.
         *
         * @param sdkClientBuilder the application's asynchronous client builder
         * @return the built client
         * @throws ElastiCacheIntegrationsConfigError if an option or the client cannot be used
         * @throws ElastiCacheIntegrationsError if the cache connection or authentication fails
         */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                C installAsync(B sdkClientBuilder) {
            requireBuilder(sdkClientBuilder);
            EndpointParser.ParsedEndpoint cacheEndpoint = validateOptions();
            SSLContext sslContext = CacheTls.create(verify, caCerts);

            ManagedIamAuthProvider auth = new ManagedIamAuthProvider();
            ElastiCacheIntegrationsAsyncHandle handle;
            try {
                handle =
                        ElastiCacheIntegrationsAsyncHandle.builder()
                                .host(cacheEndpoint.getHost())
                                .port(cacheEndpoint.getPort())
                                .sslContext(sslContext)
                                .managedAuthProvider(auth)
                                .overrides(clientConfiguration.getCachePolicyOverrides())
                                .requestTimeoutMs(installRequestTimeoutMs())
                                .reconnectPolicy(installReconnectPolicy())
                                .stalenessDurationSeconds(clientConfiguration.getCachePolicyTimeoutSeconds())
                                .originHttpClient(originAsyncHttpClient)
                                .executor(asyncExecutor)
                                .maxBufferedRequestBytes(asyncMaxBufferedRequestBytes)
                                .maxConcurrentBodyCollectors(asyncMaxConcurrentBodyCollectors)
                                .build();
            } catch (RuntimeException | Error failure) {
                auth.close();
                throw failure;
            }
            if (asyncHandleObserver != null) {
                asyncHandleObserver.accept(handle);
            }

            ClientOverrideConfiguration original = sdkClientBuilder.overrideConfiguration();
            C client;
            try {
                client =
                        sdkClientBuilder
                                .overrideConfiguration(withInterceptor(original, handle.getInterceptor()))
                                .httpClientBuilder(new PrebuiltAsyncHttpClient(handle.getHttpClient()))
                                .build();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(handle, failure);
                ElastiCacheIntegrationsConfigError translated =
                        translateBuildFailure(failure, "originAsyncHttpClient");
                if (translated != null) {
                    throw translated;
                }
                throw failure;
            } finally {
                // The built client holds the cache; the application's builder goes back to how it was,
                // so it can build or install another client.
                sdkClientBuilder
                        .overrideConfiguration(original)
                        .httpClientBuilder((SdkAsyncHttpClient.Builder<?>) null);
            }

            try {
                bindAndOpen(client, auth, handle::open);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(client, failure);
                throw failure;
            }
            return client;
        }

        private static void requireBuilder(Object sdkClientBuilder) {
            if (sdkClientBuilder == null) {
                throw new NullPointerException("sdkClientBuilder");
            }
        }

        private EndpointParser.ParsedEndpoint validateOptions() {
            if (endpoint == null || endpoint.isEmpty()) {
                throw new ElastiCacheIntegrationsConfigError("endpoint is required");
            }
            if (cacheName == null || cacheName.isEmpty()) {
                throw new ElastiCacheIntegrationsConfigError("cacheName is required");
            }
            try {
                return EndpointParser.parseEndpoint(endpoint);
            } catch (ElastiCacheIntegrationsError invalid) {
                throw new ElastiCacheIntegrationsConfigError(invalid.getMessage(), invalid);
            }
        }

        private int installRequestTimeoutMs() {
            // The configuration bounds the timeout to Integer.MAX_VALUE.
            return Math.toIntExact(clientConfiguration.getCacheRequestTimeoutMs());
        }

        private ReconnectPolicy installReconnectPolicy() {
            return ReconnectPolicy.builder()
                    .connectionTimeoutMs(clientConfiguration.getConnectionTimeoutMs())
                    .fallbackDurationSeconds(clientConfiguration.getFallbackDurationSeconds())
                    .build();
        }

        /**
         * Read the built client's region and credentials, create the token manager from them, then open
         * the session.
         */
        private void bindAndOpen(SdkClient client, ManagedIamAuthProvider auth, Runnable open) {
            SdkServiceClientConfiguration serviceConfiguration = client.serviceClientConfiguration();
            if (!(serviceConfiguration instanceof AwsServiceClientConfiguration)) {
                throw new ElastiCacheIntegrationsConfigError(
                        "The SDK client is not an AWS SDK client, so the cache's IAM token cannot be signed.");
            }
            AwsServiceClientConfiguration aws = (AwsServiceClientConfiguration) serviceConfiguration;
            Region region = aws.region();
            if (region == null) {
                throw new ElastiCacheIntegrationsConfigError(NO_REGION);
            }
            IdentityProvider<? extends AwsCredentialsIdentity> credentials = aws.credentialsProvider();
            if (credentials == null || credentials instanceof AnonymousCredentialsProvider) {
                throw new ElastiCacheIntegrationsConfigError(NO_CREDENTIALS);
            }

            IamAuthTokenProvider.Builder iam =
                    IamAuthTokenProvider.builder()
                            .cacheName(cacheName)
                            .userId(userId)
                            .region(region)
                            .credentialsProvider(asAwsCredentialsProvider(credentials));
            if (clientConfiguration.getIamTokenRefreshDelay() != null) {
                iam.refreshAfter(clientConfiguration.getIamTokenRefreshDelay());
            }
            auth.bind(iam.build());
            open.run();
        }

        /**
         * Set the cache host.
         *
         * <p>Use with {@link #port(int)}, or use {@link #endpoint(String)} instead.
         *
         * @param host the cache host
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the cache port (default: 6379).
         *
         * <p>Use with {@link #host(String)}, or use {@link #endpoint(String)} instead.
         *
         * @param port the cache port
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Set the SSL context (optional, uses default TLS if not specified).
         *
         * @param sslContext the TLS context for the cache connection
         * @return this builder
         */
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
         *
         * @param authProvider supplies AUTH credentials for each handshake
         * @return this builder
         */
        public Builder authProvider(Supplier<AuthCredentials> authProvider) {
            if (authProvider instanceof IamAuthTokenProvider) {
                return authProvider((IamAuthTokenProvider) authProvider);
            }
            this.authProvider = authProvider;
            this.iamAuthProvider = null;
            return this;
        }

        /**
         * Set an IAM provider whose auto-refresh lifecycle Cache owns.
         *
         * @param authProvider the IAM token provider
         * @return this builder
         */
        public Builder authProvider(IamAuthTokenProvider authProvider) {
            this.iamAuthProvider = authProvider;
            this.authProvider = null;
            return this;
        }

        /**
         * Set static auth credentials (convenience for non-IAM auth).
         *
         * @param userId the user ID for AUTH
         * @param authToken the password or token for AUTH
         * @return this builder
         */
        public Builder authCredentials(String userId, String authToken) {
            this.authProvider = () -> new AuthCredentials(userId, authToken);
            this.iamAuthProvider = null;
            return this;
        }

        /**
         * Set optional settings that change what Cache caches: which tables and operations to cache,
         * and the cache entry lifetimes.
         *
         * <p>{@code cached_tables} and {@code cached_operations} are allowlists. Each empty or absent
         * means everything, so keeping a table out of the cache means naming the tables that should be
         * cached rather than naming the one that should not. The lifetimes ({@code item_ttl}, {@code
         * item_negative_ttl}, {@code query_ttl}, {@code scan_ttl}) and {@code item_write_behavior}
         * apply connection-wide, or per table under {@code table_overrides}. The settings are sent
         * through the internal {@code aws.config} command.
         *
         * <p>The map is sent as given. A field name the proxy does not recognise is ignored, so a typo
         * or a stale key is dropped without an error.
         *
         * @param overrides the cache policy overrides
         * @return this builder
         */
        public Builder overrides(Map<String, Object> overrides) {
            this.overrides = overrides;
            return this;
        }

        /**
         * Set connect timeout in milliseconds (default: 5000).
         *
         * @param connectTimeoutMs the connect timeout in milliseconds
         * @return this builder
         */
        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /**
         * Set the HELLO/authentication and configuration timeout in milliseconds (default: 5000).
         *
         * @param initializationTimeoutMs the initialization timeout in milliseconds
         * @return this builder
         */
        public Builder initializationTimeoutMs(int initializationTimeoutMs) {
            this.initializationTimeoutMs = initializationTimeoutMs;
            return this;
        }

        /**
         * Set the maximum time, in milliseconds, to wait for each Cache request response (default:
         * 5000).
         *
         * @param requestTimeoutMs the request timeout in milliseconds
         * @return this builder
         */
        public Builder requestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        /**
         * Set reconnect and origin-fallback behavior for sync and async clients.
         *
         * @param reconnectPolicy the reconnect policy
         * @return this builder
         * @throws NullPointerException if {@code reconnectPolicy} is null
         */
        public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
            if (reconnectPolicy == null) {
                throw new NullPointerException("reconnectPolicy");
            }
            this.reconnectPolicy = reconnectPolicy;
            return this;
        }

        /**
         * Set how long, in seconds, Cache may use the resolved config without confirmation from the
         * cache. After this time, Cache reconnects to get a current config. Defaults to 3600 seconds
         * and must be a positive whole number.
         *
         * @param stalenessDurationSeconds the staleness window in seconds
         * @return this builder
         */
        public Builder stalenessDurationSeconds(long stalenessDurationSeconds) {
            this.stalenessDurationSeconds = stalenessDurationSeconds;
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

        /**
         * Build an asynchronous SDK client with Cache enabled.
         *
         * @param sdkClientBuilder the asynchronous SDK client builder, such as {@code
         *     DynamoDbAsyncClient.builder()}
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return the SDK client and its Cache handle
         */
        public <B extends AwsClientBuilder<B, C> & SdkAsyncClientBuilder<B, C>, C extends SdkClient>
                AsyncResult<C> buildAsync(B sdkClientBuilder) {
            return buildAsync(sdkClientBuilder, null);
        }

        /**
         * Build an asynchronous SDK client with Cache enabled and additional SDK configuration.
         *
         * @param sdkClientBuilder the asynchronous SDK client builder, such as {@code
         *     DynamoDbAsyncClient.builder()}
         * @param sdkConfigurer additional SDK client configuration (may be null)
         * @param <B> the SDK client builder type
         * @param <C> the SDK client type
         * @return the SDK client and its Cache handle
         */
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
     * Adapt the client's identity provider to the credentials provider the IAM toolkit takes. Each
     * call resolves the identity again, so rotated credentials reach each newly signed token.
     */
    private static AwsCredentialsProvider asAwsCredentialsProvider(
            IdentityProvider<? extends AwsCredentialsIdentity> provider) {
        if (provider instanceof AwsCredentialsProvider) {
            return (AwsCredentialsProvider) provider;
        }
        return () -> {
            AwsCredentialsIdentity identity;
            try {
                identity = provider.resolveIdentity().join();
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : e;
            }
            AwsCredentials credentials;
            if (identity instanceof AwsSessionCredentialsIdentity) {
                AwsSessionCredentialsIdentity session = (AwsSessionCredentialsIdentity) identity;
                credentials =
                        AwsSessionCredentials.create(
                                session.accessKeyId(), session.secretAccessKey(), session.sessionToken());
            } else {
                credentials =
                        AwsBasicCredentials.create(identity.accessKeyId(), identity.secretAccessKey());
            }
            return credentials;
        };
    }

    private static ClientOverrideConfiguration withInterceptor(
            ClientOverrideConfiguration existing, ExecutionInterceptor interceptor) {
        // Copy the builder's own configuration so its interceptors, retries, timeouts and metrics
        // survive, then add the cache interceptor.
        return existing.toBuilder().addExecutionInterceptor(interceptor).build();
    }

    /**
     * Return the configuration error for an SDK build failure the application can fix, or null for
     * any other failure. The SDK rejects a builder that has both an HTTP client and an HTTP client
     * builder, and fails to build a client when no region resolves.
     */
    private static ElastiCacheIntegrationsConfigError translateBuildFailure(
            Throwable failure, String originOption) {
        if (failure instanceof SdkClientException
                && failure.getMessage() != null
                && failure.getMessage().startsWith("Unable to load region")) {
            return new ElastiCacheIntegrationsConfigError(NO_REGION, failure);
        }
        if (failure instanceof IllegalArgumentException
                && failure.getMessage() != null
                && failure.getMessage().contains("can't both be configured")
                && failure.getMessage().toLowerCase(Locale.ROOT).contains("httpclientbuilder")) {
            return new ElastiCacheIntegrationsConfigError(
                    "This AWS SDK client builder already has an httpClient, which ElastiCache Integrations"
                            + " must replace. Remove it from the builder and pass that client as "
                            + originOption
                            + " instead.",
                    failure);
        }
        return null;
    }

    private static void closeAfterFailure(AutoCloseable owner, Throwable failure) {
        try {
            owner.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /** Hands the SDK the transport Photon already built. The SDK closes it with the client. */
    private static final class PrebuiltHttpClient
            implements SdkHttpClient.Builder<PrebuiltHttpClient> {
        private final SdkHttpClient client;

        private PrebuiltHttpClient(SdkHttpClient client) {
            this.client = client;
        }

        @Override
        public SdkHttpClient build() {
            return client;
        }

        @Override
        public SdkHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return client;
        }
    }

    /** Asynchronous counterpart of {@link PrebuiltHttpClient}. */
    private static final class PrebuiltAsyncHttpClient
            implements SdkAsyncHttpClient.Builder<PrebuiltAsyncHttpClient> {
        private final SdkAsyncHttpClient client;

        private PrebuiltAsyncHttpClient(SdkAsyncHttpClient client) {
            this.client = client;
        }

        @Override
        public SdkAsyncHttpClient build() {
            return client;
        }

        @Override
        public SdkAsyncHttpClient buildWithDefaults(AttributeMap serviceDefaults) {
            return client;
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
