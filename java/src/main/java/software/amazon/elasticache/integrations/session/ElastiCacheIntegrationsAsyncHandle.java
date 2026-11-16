package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Owns the session, interceptor and transport that {@link ElastiCacheIntegrationsClient} installs
 * into an asynchronous SDK client builder.
 *
 * <p>Lifecycle operations are serialized. Requests remain concurrent through the asynchronous HTTP
 * transport. Closing the handle closes the transport, which closes the session, then the IAM token
 * manager, then the origin transport and executor if the handle created them.
 */
final class ElastiCacheIntegrationsAsyncHandle implements Closeable {

    static final int DEFAULT_MAX_BUFFERED_REQUEST_BYTES = 16 * 1024 * 1024;
    static final int DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS = 8;

    private final ElastiCacheIntegrationsSession session;
    private final ElastiCacheIntegrationsAsyncInterceptor interceptor;
    private final ElastiCacheIntegrationsAsyncHttpClient httpClient;
    private final ManagedIamAuthProvider ownedAuthProvider;
    private State state = State.CREATED;

    private ElastiCacheIntegrationsAsyncHandle(
            ElastiCacheIntegrationsSession session,
            SdkAsyncHttpClient originClient,
            boolean ownsOriginClient,
            Executor executor,
            ExecutorService ownedExecutor,
            int maxBufferedRequestBytes,
            int maxConcurrentBodyCollectors,
            ManagedIamAuthProvider ownedAuthProvider) {
        this.session = session;
        this.interceptor =
                new ElastiCacheIntegrationsAsyncInterceptor(
                        session,
                        originClient,
                        ownsOriginClient,
                        executor,
                        ownedExecutor,
                        maxBufferedRequestBytes,
                        maxConcurrentBodyCollectors,
                        true,
                        ownedAuthProvider);
        this.httpClient = interceptor.getHttpClient();
        this.ownedAuthProvider = ownedAuthProvider;
    }

    /**
     * Get the interceptor to add to the SDK client builder's override configuration.
     *
     * @return the interceptor
     */
    public ElastiCacheIntegrationsAsyncInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * Get the Cache-first asynchronous transport.
     *
     * @return the asynchronous HTTP client
     */
    public ElastiCacheIntegrationsAsyncHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Get the underlying Cache session.
     *
     * @return the session
     */
    public ElastiCacheIntegrationsSession getSession() {
        return session;
    }

    /** Open the Cache session. */
    public synchronized void open() {
        if (state == State.OPEN) {
            throw new ElastiCacheIntegrationsError("Handle already opened");
        }
        if (state == State.CLOSED) {
            throw new ElastiCacheIntegrationsError("Handle already closed");
        }
        session.open();
        state = State.OPEN;
    }

    /** Get the IAM token manager adapter this handle owns, or null when auth is caller-owned. */
    ManagedIamAuthProvider getOwnedAuthProvider() {
        return ownedAuthProvider;
    }

    /** Close the transport, session, token manager and owned async resources. Idempotent. */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        try {
            httpClient.close();
        } finally {
            state = State.CLOSED;
        }
    }

    /**
     * Check if the handle is open and ready.
     *
     * @return true while the handle is open and its session is open
     */
    public synchronized boolean isOpen() {
        return state == State.OPEN && session.isOpen();
    }

    /**
     * Create a new async handle builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for asynchronous Cache handles. */
    public static final class Builder {
        private String host;
        private int port = 6379;
        private SSLContext sslContext;
        private Supplier<AuthCredentials> authProvider;
        private IamAuthTokenProvider iamAuthProvider;
        private ManagedIamAuthProvider managedAuthProvider;
        private Map<String, Object> overrides;
        private int requestTimeoutMs = ElastiCacheIntegrationsConnector.DEFAULT_REQUEST_TIMEOUT_MS;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
        private long stalenessDurationSeconds =
                ElastiCacheIntegrationsSession.DEFAULT_STALENESS_DURATION_SECONDS;
        private SdkAsyncHttpClient originHttpClient;
        private Executor executor;
        private int maxBufferedRequestBytes = DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int maxConcurrentBodyCollectors = DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;

        private Builder() {}

        /**
         * Set the cache host (required).
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
         * Set the auth provider.
         *
         * <p>Other caller-supplied providers remain caller-owned.
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
         * Set an IAM provider whose auto-refresh manager is owned by this handle.
         *
         * @param authProvider the IAM token provider
         * @return this builder
         */
        public Builder authProvider(IamAuthTokenProvider authProvider) {
            this.iamAuthProvider = authProvider;
            this.authProvider = null;
            return this;
        }

        /** Set a token manager adapter whose lifecycle this handle owns. */
        Builder managedAuthProvider(ManagedIamAuthProvider managedAuthProvider) {
            this.managedAuthProvider = managedAuthProvider;
            this.authProvider = null;
            this.iamAuthProvider = null;
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
         * Set reconnect and origin-fallback behavior.
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
         * Set how long, in seconds, the client may use the resolved config without confirmation from
         * the cache. After this time, the client reconnects to get a current config. Defaults to 3600
         * seconds and must be a positive whole number.
         *
         * @param stalenessDurationSeconds the staleness window in seconds
         * @return this builder
         */
        public Builder stalenessDurationSeconds(long stalenessDurationSeconds) {
            this.stalenessDurationSeconds = stalenessDurationSeconds;
            return this;
        }

        /**
         * Set a caller-managed asynchronous HTTP client for origin fallback.
         *
         * @param originHttpClient the asynchronous HTTP client for origin requests
         * @return this builder
         */
        public Builder originHttpClient(SdkAsyncHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor used for blocking Cache requests.
         *
         * @param executor the executor for blocking Cache requests
         * @return this builder
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Set the maximum async request body Cache buffers (default: 16 MiB).
         *
         * @param maxBufferedRequestBytes the body size limit in bytes. A larger body goes to the
         *     origin.
         * @return this builder
         */
        public Builder maxBufferedRequestBytes(int maxBufferedRequestBytes) {
            this.maxBufferedRequestBytes = maxBufferedRequestBytes;
            return this;
        }

        /**
         * Set the maximum request bodies collected concurrently (default: 8).
         *
         * @param maxConcurrentBodyCollectors the limit on bodies collected at once
         * @return this builder
         */
        public Builder maxConcurrentBodyCollectors(int maxConcurrentBodyCollectors) {
            this.maxConcurrentBodyCollectors = maxConcurrentBodyCollectors;
            return this;
        }

        /**
         * Build the handle.
         *
         * @return the handle, not yet opened
         */
        public ElastiCacheIntegrationsAsyncHandle build() {
            validateConfiguration();

            ManagedIamAuthProvider ownedAuthProvider =
                    managedAuthProvider != null
                            ? managedAuthProvider
                            : iamAuthProvider != null ? new ManagedIamAuthProvider(iamAuthProvider) : null;
            Supplier<AuthCredentials> resolvedAuthProvider =
                    ownedAuthProvider != null ? ownedAuthProvider : authProvider;
            boolean ownsOriginClient = originHttpClient == null;
            SdkAsyncHttpClient resolvedOriginClient = null;
            ExecutorService ownedExecutor = null;
            try {
                ElastiCacheIntegrationsSession session =
                        createSession(resolveSslContext(), resolvedAuthProvider);
                resolvedOriginClient =
                        ownsOriginClient ? NettyNioAsyncHttpClient.create() : originHttpClient;
                ownedExecutor = executor == null ? createDefaultExecutor() : null;
                Executor resolvedExecutor = executor == null ? ownedExecutor : executor;

                return new ElastiCacheIntegrationsAsyncHandle(
                        session,
                        resolvedOriginClient,
                        ownsOriginClient,
                        resolvedExecutor,
                        ownedExecutor,
                        maxBufferedRequestBytes,
                        maxConcurrentBodyCollectors,
                        ownedAuthProvider);
            } catch (RuntimeException failure) {
                if (ownedExecutor != null) {
                    ownedExecutor.shutdownNow();
                }
                if (ownsOriginClient && resolvedOriginClient != null) {
                    resolvedOriginClient.close();
                }
                if (ownedAuthProvider != null) {
                    ownedAuthProvider.close();
                }
                throw failure;
            }
        }

        private void validateConfiguration() {
            if (host == null || host.isEmpty()) {
                throw new ElastiCacheIntegrationsError("host is required");
            }
            if (authProvider == null && iamAuthProvider == null && managedAuthProvider == null) {
                throw new ElastiCacheIntegrationsError("authProvider is required");
            }
            if (maxBufferedRequestBytes <= 0) {
                throw new ElastiCacheIntegrationsError("maxBufferedRequestBytes must be positive");
            }
            if (maxConcurrentBodyCollectors <= 0) {
                throw new ElastiCacheIntegrationsError("maxConcurrentBodyCollectors must be positive");
            }
        }

        private SSLContext resolveSslContext() {
            if (sslContext != null) {
                return sslContext;
            }
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new ElastiCacheIntegrationsError("Failed to get default SSLContext", e);
            }
        }

        private ElastiCacheIntegrationsSession createSession(
                SSLContext resolvedSslContext, Supplier<AuthCredentials> resolvedAuthProvider) {
            return new ElastiCacheIntegrationsSession(
                    () ->
                            new ElastiCacheIntegrationsConnector(
                                    host,
                                    port,
                                    resolvedSslContext,
                                    Math.toIntExact(reconnectPolicy.getConnectionTimeoutMs()),
                                    requestTimeoutMs,
                                    ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING),
                    resolvedAuthProvider,
                    overrides,
                    reconnectPolicy,
                    stalenessDurationSeconds);
        }

        private static ExecutorService createDefaultExecutor() {
            ThreadPoolExecutor executor =
                    new ThreadPoolExecutor(
                            ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING,
                            ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING,
                            60,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(),
                            new ElastiCacheIntegrationsThreadFactory());
            executor.allowCoreThreadTimeOut(true);
            return executor;
        }
    }

    private enum State {
        CREATED,
        OPEN,
        CLOSED
    }

    private static final class ElastiCacheIntegrationsThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread =
                    new Thread(
                            runnable,
                            "aws-elasticache-integration-client-async-request-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
