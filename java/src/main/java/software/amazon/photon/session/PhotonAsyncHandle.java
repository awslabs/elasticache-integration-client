package software.amazon.photon.session;

import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.exception.PhotonError;

import javax.net.ssl.SSLContext;
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

/**
 * Manages the attachment of Photon to an asynchronous AWS SDK client.
 *
 * <p>Lifecycle operations are serialized. Requests remain concurrent through
 * the asynchronous HTTP transport.
 */
public final class PhotonAsyncHandle implements Closeable {

    public static final int DEFAULT_MAX_BUFFERED_REQUEST_BYTES = 16 * 1024 * 1024;
    public static final int DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS = 8;

    private final PhotonSession session;
    private final PhotonAsyncInterceptor interceptor;
    private final PhotonAsyncHttpClient httpClient;
    private State state = State.CREATED;

    private PhotonAsyncHandle(PhotonSession session,
                              SdkAsyncHttpClient originClient,
                              boolean ownsOriginClient,
                              Executor executor,
                              ExecutorService ownedExecutor,
                              int maxBufferedRequestBytes,
                              int maxConcurrentBodyCollectors) {
        this.session = session;
        this.interceptor = new PhotonAsyncInterceptor(
                session,
                originClient,
                ownsOriginClient,
                executor,
                ownedExecutor,
                maxBufferedRequestBytes,
                maxConcurrentBodyCollectors);
        this.httpClient = interceptor.getHttpClient();
    }

    /**
     * Get the interceptor to install on the SDK client.
     */
    public PhotonAsyncInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * Get the Photon-first asynchronous HTTP transport.
     */
    public PhotonAsyncHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Get the underlying Photon session.
     */
    public PhotonSession getSession() {
        return session;
    }

    /**
     * Open the Photon session.
     */
    public synchronized void open() {
        if (state == State.OPEN) {
            throw new PhotonError("Handle already opened");
        }
        if (state == State.CLOSED) {
            throw new PhotonError("Handle already closed");
        }
        session.open();
        state = State.OPEN;
    }

    /**
     * Close the Photon session and owned async resources. Idempotent.
     */
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
                state = State.CLOSED;
            }
        }
    }

    /**
     * Check if the handle is open and ready.
     */
    public synchronized boolean isOpen() {
        return state == State.OPEN && session.isOpen();
    }

    /**
     * Create a new async handle builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for asynchronous Photon handles.
     */
    public static final class Builder {
        private String host;
        private int port = 6379;
        private SSLContext sslContext;
        private Supplier<AuthCredentials> authProvider;
        private Map<String, Object> overrides;
        private int connectTimeoutMs = 5000;
        private int initializationTimeoutMs = 5000;
        private int requestTimeoutMs = 5000;
        private ReconnectPolicy reconnectPolicy = ReconnectPolicy.defaults();
        private SdkAsyncHttpClient originHttpClient;
        private Executor executor;
        private int maxBufferedRequestBytes = DEFAULT_MAX_BUFFERED_REQUEST_BYTES;
        private int maxConcurrentBodyCollectors = DEFAULT_MAX_CONCURRENT_BODY_COLLECTORS;

        private Builder() {
        }

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

        public Builder authProvider(Supplier<AuthCredentials> authProvider) {
            this.authProvider = authProvider;
            return this;
        }

        public Builder authCredentials(String userId, String authToken) {
            this.authProvider = () -> new AuthCredentials(userId, authToken);
            return this;
        }

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

        /**
         * Set reconnect and circuit-breaker behavior.
         */
        public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
            if (reconnectPolicy == null) {
                throw new NullPointerException("reconnectPolicy");
            }
            this.reconnectPolicy = reconnectPolicy;
            return this;
        }

        /**
         * Set a caller-managed asynchronous HTTP client for origin fallback.
         */
        public Builder originHttpClient(SdkAsyncHttpClient originHttpClient) {
            this.originHttpClient = originHttpClient;
            return this;
        }

        /**
         * Set the caller-managed executor used for blocking Photon requests.
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Set the maximum async request body Photon buffers (default: 16 MiB).
         */
        public Builder maxBufferedRequestBytes(int maxBufferedRequestBytes) {
            this.maxBufferedRequestBytes = maxBufferedRequestBytes;
            return this;
        }

        /**
         * Set the maximum request bodies collected concurrently (default: 8).
         */
        public Builder maxConcurrentBodyCollectors(int maxConcurrentBodyCollectors) {
            this.maxConcurrentBodyCollectors = maxConcurrentBodyCollectors;
            return this;
        }

        /**
         * Build the handle.
         */
        public PhotonAsyncHandle build() {
            validateConfiguration();

            PhotonSession session = createSession(resolveSslContext());
            boolean ownsOriginClient = originHttpClient == null;
            SdkAsyncHttpClient resolvedOriginClient =
                    ownsOriginClient ? NettyNioAsyncHttpClient.create() : originHttpClient;
            ExecutorService ownedExecutor = executor == null ? createDefaultExecutor() : null;
            Executor resolvedExecutor = executor == null ? ownedExecutor : executor;

            return new PhotonAsyncHandle(
                    session,
                    resolvedOriginClient,
                    ownsOriginClient,
                    resolvedExecutor,
                    ownedExecutor,
                    maxBufferedRequestBytes,
                    maxConcurrentBodyCollectors);
        }

        private void validateConfiguration() {
            if (host == null || host.isEmpty()) {
                throw new PhotonError("host is required");
            }
            if (authProvider == null) {
                throw new PhotonError("authProvider is required");
            }
            if (maxBufferedRequestBytes <= 0) {
                throw new PhotonError("maxBufferedRequestBytes must be positive");
            }
            if (maxConcurrentBodyCollectors <= 0) {
                throw new PhotonError("maxConcurrentBodyCollectors must be positive");
            }
        }

        private SSLContext resolveSslContext() {
            if (sslContext != null) {
                return sslContext;
            }
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException e) {
                throw new PhotonError("Failed to get default SSLContext", e);
            }
        }

        private PhotonSession createSession(SSLContext resolvedSslContext) {
            return new PhotonSession(
                    () -> new PhotonConnector(
                            host, port, resolvedSslContext, connectTimeoutMs,
                            initializationTimeoutMs, requestTimeoutMs,
                            PhotonConnector.DEFAULT_MAX_PENDING),
                    authProvider,
                    overrides,
                    reconnectPolicy);
        }

        private static ExecutorService createDefaultExecutor() {
            return new ThreadPoolExecutor(
                    0,
                    PhotonConnector.DEFAULT_MAX_PENDING,
                    60,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    new PhotonThreadFactory());
        }
    }

    private enum State {
        CREATED,
        OPEN,
        CLOSED
    }

    private static final class PhotonThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "photon-async-request-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
