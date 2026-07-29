package software.amazon.photon.session;

import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;

import java.io.Closeable;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns a PhotonConnection and manages its lifecycle.
 *
 * <p>A session orchestrates connection bring-up (transport connect → AUTH →
 * the mandatory {@code photon.config} handshake) and exposes a synchronous
 * {@link #request(PhotonRequest)} method for the SDK interceptor to call.
 *
 * <p>Lifecycle: {@code CREATED} → {@code OPEN} → {@code CLOSED}
 *
 * <p>Scope: no predicate filtering, degradation, circuit breaking, retry,
 * pass-through, or periodic config refresh. The resolved config is fetched
 * once and stored for protocol compliance only. Errors propagate.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonSession session = new PhotonSession(connection, () -> authToken, configOverrides);
 * session.open();
 * try {
 *     PhotonResponse response = session.request(photonRequest);
 * } finally {
 *     session.close();
 * }
 * }</pre>
 */
public class PhotonSession implements Closeable {

    /**
     * Session lifecycle state.
     */
    public enum State {
        /** Session created but not yet opened. */
        CREATED,
        /** Session is open and ready for requests. */
        OPEN,
        /** Session is closed. */
        CLOSED
    }

    private final PhotonConnection connection;
    private final Supplier<String> authProvider;
    private final Map<String, Object> configOverrides;

    private volatile State state = State.CREATED;
    private ResolvedConfig resolvedConfig;

    /**
     * Create a session with the given connection and auth provider.
     *
     * @param connection      the underlying connection (not yet connected)
     * @param authProvider    supplies the AUTH token
     * @param configOverrides optional config overrides (may be null)
     */
    public PhotonSession(PhotonConnection connection, Supplier<String> authProvider,
                         Map<String, Object> configOverrides) {
        this.connection = connection;
        this.authProvider = authProvider;
        this.configOverrides = configOverrides != null ? configOverrides : Collections.emptyMap();
    }

    /**
     * Create a session with the given connection and static auth token.
     *
     * @param connection      the underlying connection (not yet connected)
     * @param authToken       the AUTH token
     * @param configOverrides optional config overrides (may be null)
     */
    public PhotonSession(PhotonConnection connection, String authToken,
                         Map<String, Object> configOverrides) {
        this(connection, () -> authToken, configOverrides);
    }

    /**
     * Create a session with default config overrides.
     *
     * @param connection   the underlying connection
     * @param authProvider supplies the AUTH token
     */
    public PhotonSession(PhotonConnection connection, Supplier<String> authProvider) {
        this(connection, authProvider, null);
    }

    /**
     * Create a session with default config overrides and static auth token.
     *
     * @param connection the underlying connection
     * @param authToken  the AUTH token
     */
    public PhotonSession(PhotonConnection connection, String authToken) {
        this(connection, authToken, null);
    }

    /**
     * Get the current session state.
     */
    public State getState() {
        return state;
    }

    /**
     * Get the resolved configuration from the config handshake.
     *
     * @return the resolved config, or null if not yet opened
     */
    public ResolvedConfig getResolvedConfig() {
        return resolvedConfig;
    }

    /**
     * Open the session: connect, authenticate, and perform the config handshake.
     *
     * @throws PhotonError if already opened/closed or if any handshake step fails
     */
    public void open() {
        if (state != State.CREATED) {
            throw new PhotonError("open() called on a " + state.name().toLowerCase() + " session");
        }

        connection.connect();
        try {
            connection.authenticate(authProvider.get());
            resolvedConfig = connection.configureAndStartReader(configOverrides);
        } catch (Exception e) {
            // Close on failure to clean up partially-opened state
            try {
                close();
            } catch (Exception closeEx) {
                // Preserve the original exception
            }
            throw e;
        }
        state = State.OPEN;
    }

    /**
     * Send a request to the cache.
     *
     * <p>Requests are forwarded unconditionally (no filtering). Connection
     * and protocol errors propagate.
     *
     * @param request the request to send
     * @return the cache response
     * @throws PhotonError if the session is not open
     */
    public PhotonResponse request(PhotonRequest request) {
        if (state != State.OPEN) {
            throw new PhotonError("request() called on a " + state.name().toLowerCase() + " session");
        }
        return connection.request(request);
    }

    /**
     * Close the session. Idempotent.
     */
    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        connection.close();
    }

    /**
     * Check if the session is open and ready for requests.
     */
    public boolean isOpen() {
        return state == State.OPEN;
    }
}
