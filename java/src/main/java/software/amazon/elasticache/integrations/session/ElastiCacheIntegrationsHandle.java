package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.auth.IamAuthTokenProvider;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;

/**
 * Manages the attachment of Cache to an AWS SDK client.
 *
 * <p>ElastiCacheIntegrationsHandle creates and manages the lifecycle of a
 * ElastiCacheIntegrationsSession and its associated ElastiCacheIntegrationsConnector. It provides a
 * clean API for setting up Cache with an SDK client.
 *
 * <p>AWS SDK v2 for Java requires the interceptor and HTTP transport to be configured at client
 * build time, so this handle is used during construction:
 *
 * <pre>{@code
 * // Cache creates and owns auto-refresh for a concrete IAM provider
 * IamAuthTokenProvider iamAuth = IamAuthTokenProvider.builder()
 *     .cacheName("my-cache")
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * ElastiCacheIntegrationsHandle handle = ElastiCacheIntegrationsHandle.builder()
 *     .host("my-cache.serverless.use1.cache.amazonaws.com")
 *     .port(6379)
 *     .authProvider(iamAuth)
 *     .build();
 *
 * // Open the session
 * handle.open();
 *
 * // Build the SDK client with the Cache-first, origin-fallback transport
 * DynamoDbClient client = DynamoDbClient.builder()
 *     .overrideConfiguration(c -> c.addExecutionInterceptor(handle.getInterceptor()))
 *     .httpClient(handle.getHttpClient())
 *     .build();
 *
 * // Use the client...
 *
 * // Clean up (close the handle, then the client)
 * handle.close();
 * client.close();
 * }</pre>
 *
 * <p>For simpler lifecycle management, consider using try-with-resources or integrating with your
 * application's shutdown hooks.
 */
public class ElastiCacheIntegrationsHandle implements Closeable {

    private final ElastiCacheIntegrationsSession session;
    private final ElastiCacheIntegrationsInterceptor interceptor;
    private final ElastiCacheIntegrationsHttpClient httpClient;
    private final ManagedIamAuthProvider ownedAuthProvider;
    private boolean opened = false;
    private boolean closed = false;

    private ElastiCacheIntegrationsHandle(
            ElastiCacheIntegrationsSession session,
            SdkHttpClient originClient,
            boolean ownsOriginClient,
            ManagedIamAuthProvider ownedAuthProvider) {
        this.session = session;
        this.interceptor =
                new ElastiCacheIntegrationsInterceptor(session, originClient, ownsOriginClient);
        this.httpClient = interceptor.getHttpClient();
        this.ownedAuthProvider = ownedAuthProvider;
    }

    /**
     * Get the interceptor to add to an SDK client.
     *
     * <p>The interceptor should be added during client construction via {@code
     * overrideConfiguration(c -> c.addExecutionInterceptor(interceptor))}. The client must also use
     * {@link #getHttpClient()} as its HTTP transport.
     */
    public ElastiCacheIntegrationsInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * Get the HTTP client that attempts Cache and falls back to the origin service.
     *
     * <p>This transport must be installed together with {@link #getInterceptor()}. Installing only
     * the interceptor bypasses Cache and sends every request through the SDK's configured origin
     * transport.
     */
    public ElastiCacheIntegrationsHttpClient getHttpClient() {
        return httpClient;
    }

    /** Get the underlying session. */
    public ElastiCacheIntegrationsSession getSession() {
        return session;
    }

    /**
     * Open the Cache session (connect, HELLO/authenticate, config exchange).
     *
     * <p>This must be called before using the SDK client.
     *
     * @throws ElastiCacheIntegrationsError if opening fails
     */
    public void open() {
        if (opened) {
            throw new ElastiCacheIntegrationsError("Handle already opened");
        }
        if (closed) {
            throw new ElastiCacheIntegrationsError("Handle already closed");
        }
        session.open();
        opened = true;
    }

    /** Close the Cache session. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            session.close();
        } finally {
            try {
                httpClient.close();
            } finally {
                if (ownedAuthProvider != null) {
                    ownedAuthProvider.close();
                }
            }
        }
    }

    /** Check if the handle is open and ready. */
    public boolean isOpen() {
        return opened && !closed && session.isOpen();
    }

    /** Create a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for ElastiCacheIntegrationsHandle. */
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
        private SdkHttpClient originHttpClient;

        private Builder() {}

        /** Set the cache host (required). */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /** Set the cache port (default: 6379). */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Set the SSL context (optional, uses default if not specified). */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the auth provider (required).
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
         * Build the handle.
         *
         * @throws ElastiCacheIntegrationsError if required fields are missing
         */
        public ElastiCacheIntegrationsHandle build() {
            if (host == null || host.isEmpty()) {
                throw new ElastiCacheIntegrationsError("host is required");
            }
            if (authProvider == null && iamAuthProvider == null) {
                throw new ElastiCacheIntegrationsError("authProvider is required");
            }

            SSLContext ssl = sslContext;
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    throw new ElastiCacheIntegrationsError("Failed to get default SSLContext", e);
                }
            }

            ManagedIamAuthProvider ownedAuthProvider =
                    iamAuthProvider != null ? new ManagedIamAuthProvider(iamAuthProvider) : null;
            Supplier<AuthCredentials> resolvedAuthProvider =
                    ownedAuthProvider != null ? ownedAuthProvider : authProvider;
            SdkHttpClient origin = null;
            boolean ownsOrigin = originHttpClient == null;
            try {
                SSLContext resolvedSsl = ssl;
                ElastiCacheIntegrationsSession session =
                        new ElastiCacheIntegrationsSession(
                                () ->
                                        new ElastiCacheIntegrationsConnector(
                                                host,
                                                port,
                                                resolvedSsl,
                                                connectTimeoutMs,
                                                initializationTimeoutMs,
                                                requestTimeoutMs,
                                                ElastiCacheIntegrationsConnector.DEFAULT_MAX_PENDING),
                                resolvedAuthProvider,
                                overrides,
                                reconnectPolicy,
                                stalenessDurationSeconds);
                origin = ownsOrigin ? ApacheHttpClient.create() : originHttpClient;
                return new ElastiCacheIntegrationsHandle(session, origin, ownsOrigin, ownedAuthProvider);
            } catch (RuntimeException failure) {
                if (ownsOrigin && origin != null) {
                    origin.close();
                }
                if (ownedAuthProvider != null) {
                    ownedAuthProvider.close();
                }
                throw failure;
            }
        }
    }
}
