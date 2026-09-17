package software.amazon.photon.session;

import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.exception.PhotonError;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Manages the attachment of Photon to an AWS SDK client.
 *
 * <p>PhotonHandle creates and manages the lifecycle of a PhotonSession and
 * its associated PhotonConnector. It provides a clean API for setting up
 * Photon with an SDK client.
 *
 * <p>AWS SDK v2 for Java requires the interceptor and HTTP transport to be
 * configured at client build time, so this handle is used during construction:
 *
 * <pre>{@code
 * // Create the handle with an IamAuthTokenProvider
 * IamAuthTokenProvider iamAuth = IamAuthTokenProvider.builder()
 *     .cacheName("my-cache")
 *     .region(Region.US_EAST_1)
 *     .build();
 *
 * PhotonHandle handle = PhotonHandle.builder()
 *     .host("my-cache.serverless.use1.cache.amazonaws.com")
 *     .port(6379)
 *     .authProvider(iamAuth)
 *     .build();
 *
 * // Open the session
 * handle.open();
 *
 * // Build the SDK client with the Photon-first, origin-fallback transport
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
 * <p>For simpler lifecycle management, consider using try-with-resources
 * or integrating with your application's shutdown hooks.
 */
public class PhotonHandle implements Closeable {

    private final PhotonSession session;
    private final PhotonInterceptor interceptor;
    private final PhotonHttpClient httpClient;
    private boolean opened = false;
    private boolean closed = false;

    private PhotonHandle(PhotonSession session, SdkHttpClient originClient,
                         boolean ownsOriginClient) {
        this.session = session;
        this.interceptor = new PhotonInterceptor(session, originClient, ownsOriginClient);
        this.httpClient = interceptor.getHttpClient();
    }

    /**
     * Get the interceptor to add to an SDK client.
     *
     * <p>The interceptor should be added during client construction via
     * {@code overrideConfiguration(c -> c.addExecutionInterceptor(interceptor))}.
     * The client must also use {@link #getHttpClient()} as its HTTP transport.
     */
    public PhotonInterceptor getInterceptor() {
        return interceptor;
    }

    /**
     * Get the HTTP client that attempts Photon and falls back to the origin service.
     *
     * <p>This transport must be installed together with {@link #getInterceptor()}.
     * Installing only the interceptor bypasses Photon and sends every request
     * through the SDK's configured origin transport.
     */
    public PhotonHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Get the underlying session.
     */
    public PhotonSession getSession() {
        return session;
    }

    /**
     * Open the Photon session (connect, authenticate, config handshake).
     *
     * <p>This must be called before using the SDK client.
     *
     * @throws PhotonError if opening fails
     */
    public void open() {
        if (opened) {
            throw new PhotonError("Handle already opened");
        }
        if (closed) {
            throw new PhotonError("Handle already closed");
        }
        session.open();
        opened = true;
    }

    /**
     * Close the Photon session. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            session.close();
        } finally {
            httpClient.close();
        }
    }

    /**
     * Check if the handle is open and ready.
     */
    public boolean isOpen() {
        return opened && !closed && session.isOpen();
    }

    /**
     * Create a new builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PhotonHandle.
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
        private SdkHttpClient originHttpClient;

        private Builder() {}

        /**
         * Set the cache host (required).
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Set the cache port (default: 6379).
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Set the SSL context (optional, uses default if not specified).
         */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /**
         * Set the auth provider (required).
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
         * Set config overrides (optional).
         */
        public Builder overrides(Map<String, Object> overrides) {
            this.overrides = overrides;
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
         * Set reconnect and origin-fallback behavior.
         */
        public Builder reconnectPolicy(ReconnectPolicy reconnectPolicy) {
            if (reconnectPolicy == null) {
                throw new NullPointerException("reconnectPolicy");
            }
            this.reconnectPolicy = reconnectPolicy;
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
         * Build the handle.
         *
         * @throws PhotonError if required fields are missing
         */
        public PhotonHandle build() {
            if (host == null || host.isEmpty()) {
                throw new PhotonError("host is required");
            }
            if (authProvider == null) {
                throw new PhotonError("authProvider is required");
            }

            SSLContext ssl = sslContext;
            if (ssl == null) {
                try {
                    ssl = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    throw new PhotonError("Failed to get default SSLContext", e);
                }
            }

            SSLContext resolvedSsl = ssl;
            PhotonSession session = new PhotonSession(
                    () -> new PhotonConnector(
                            host, port, resolvedSsl, connectTimeoutMs, initializationTimeoutMs,
                            requestTimeoutMs, PhotonConnector.DEFAULT_MAX_PENDING),
                    authProvider,
                    overrides,
                    reconnectPolicy);
            SdkHttpClient origin = originHttpClient;
            boolean ownsOrigin = false;
            if (origin == null) {
                origin = ApacheHttpClient.create();
                ownsOrigin = true;
            }
            return new PhotonHandle(session, origin, ownsOrigin);
        }
    }
}
