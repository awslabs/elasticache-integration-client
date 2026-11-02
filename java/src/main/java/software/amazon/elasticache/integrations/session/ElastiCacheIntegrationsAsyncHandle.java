package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
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
 * Manages the attachment of Cache to an asynchronous AWS SDK client.
 *
 * <p>Lifecycle operations are serialized. Requests remain concurrent through the asynchronous HTTP
 * transport.
 */
public final class ElastiCacheIntegrationsAsyncHandle implements Closeable {

    public static final int DEFAULT_MAX_BUFFERED_REQUEST_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS = 8;

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
                        maxConcurrentBodyCollectors);
        this.httpClient = interceptor.getHttpClient();
        this.ownedAuthProvider = ownedAuthProvider;
    }

    /** Get the interceptor to install on the SDK client. */
    public ElastiCacheIntegrationsAsyncInterceptor getInterceptor() {
        return interceptor;
    }

    /** Get the Cache-first asynchronous HTTP transport. */
    public ElastiCacheIntegrationsAsyncHttpClient getHttpClient() {
        return httpClient;
    }

    /** Get the underlying Cache session. */
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

    /** Close the Cache session and owned async resources. Idempotent. */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) {
            return;
        }
        try {
            session.close();
        } finally {
            try {
                httpClient.close();
            } finally {
                try {
                    if (ownedAuthProvider != null) {
                        ownedAuthProvider.close();
                    }
                } finally {
                    state = State.CLOSED;
                }
            }
        }
    }

    /** Check if the handle is open and ready. */
    public synchronized boolean isOpen() {
        return state == State.OPEN && session.isOpen();
    }

    /** Create a new async handle builder. */
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
        private Map<String, Object> overrides;
        private int connectTimeoutMs = 5000;
        private int initializationTimeoutMs = 5000;
        private int requestTimeoutMs = 5000;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
        private long stalenessDurationSeconds =
                ElastiCacheIntegrationsSession.DEFAULT_STALENESS_DURATION_SECONDS;
        private SdkAsyncHttpClient originHttpClient;
        private Executor executor;
        private int maxBufferedRequestBytes = DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int maxConcurrentBodyCollectors = DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;

        private Builder() {}

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the auth provider.
         *
         * <p>Other caller-supplied providers remain caller-owned.
         */
        public Builder authProvider(Supplier<AuthCredentials> authProvider) {
            if (authProvider instanceof IamAuthTokenProvider) {
                return authProvider((IamAuthTokenProvider) authProvider);
            }
            this.authProvider = authProvider;
            this.iamAuthProvider = null;
            return this;
        }

        /** Set an IAM provider whose auto-refresh manager is owned by this handle. */
        public Builder authProvider(IamAuthTokenProvider authProvider) {
            this.iamAuthProvider = authProvider;
            this.authProvider = null;
            return this;
        }

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

        public Builder connectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder initializationTimeoutMs(int initializationTimeoutMs) {
            this.initializationTimeoutMs = initializationTimeoutMs;
            return this;
        }

        public Builder requestTimeoutMs(int requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        /** Set reconnect and origin-fallback behavior. */
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

        /** Set a caller-managed asynchronous HTTP client for origin fallback. */
        public Builder originHttpClient(SdkAsyncHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /** Set the caller-managed executor used for blocking Cache requests. */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** Set the maximum async request body Cache buffers (default: 16 MiB). */
        public Builder maxBufferedRequestBytes(int maxBufferedRequestBytes) {
            this.maxBufferedRequestBytes = maxBufferedRequestBytes;
            return this;
        }

        /** Set the maximum request bodies collected concurrently (default: 8). */
        public Builder maxConcurrentBodyCollectors(int maxConcurrentBodyCollectors) {
            this.maxConcurrentBodyCollectors = maxConcurrentBodyCollectors;
            return this;
        }

        /** Build the handle. */
        public ElastiCacheIntegrationsAsyncHandle build() {
            validateConfiguration();

            ManagedIamAuthProvider ownedAuthProvider =
                    iamAuthProvider != null ? new ManagedIamAuthProvider(iamAuthProvider) : null;
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
            if (authProvider == null && iamAuthProvider == null) {
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
                                    connectTimeoutMs,
                                    initializationTimeoutMs,
                                    requestTimeoutMs,
                                    ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING),
                    resolvedAuthProvider,
                    overrides,
                    reconnectPolicy,
                    stalenessDurationSeconds);
        }

        private static ExecutorService createDefaultExecutor() {
            return new ThreadPoolExecutor(
                    0,
                    ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING,
                    60,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new ElastiCacheIntegrationsThreadFactory());
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
