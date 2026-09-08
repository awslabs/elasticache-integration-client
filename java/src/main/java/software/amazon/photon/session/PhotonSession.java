package software.amazon.photon.session;

import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.predicate.PredicateEvaluator;

import java.io.Closeable;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns a PhotonConnector and manages its lifecycle.
 *
 * <p>A session orchestrates connection bring-up (transport connect → AUTH →
 * the mandatory {@code photon.config} handshake) and exposes a synchronous
 * {@link #request(PhotonRequest)} method for the SDK transport to call.
 *
 * <p>Lifecycle: {@code CREATED} → {@code OPEN} → {@code CLOSED}
 *
 * <p>Resolved CEL predicates determine which requests are sent to Photon.
 * Requests with no matching predicate are declined for origin pass-through.
 * The config is fetched and compiled once.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonSession session = new PhotonSession(connector, authProvider, overrides);
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

    private final PhotonConnector connector;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> overrides;

    private volatile State state = State.CREATED;
    private ResolvedConfig config;
    private PredicateEvaluator predicateEvaluator;

    /**
     * Create a session with the given connection and auth provider.
     *
     * @param connector       the underlying connector (not yet connected)
     * @param authProvider    supplies the AUTH credentials (userId + token)
     * @param overrides optional config overrides (may be null)
     */
    public PhotonSession(PhotonConnector connector, Supplier<AuthCredentials> authProvider,
                         Map<String, Object> overrides) {
        this.connector = connector;
        this.authProvider = authProvider;
        this.overrides = overrides != null ? overrides : Collections.emptyMap();
    }

    /**
     * Create a session with the given connection and static auth credentials.
     *
     * @param connector       the underlying connector (not yet connected)
     * @param userId          the user ID for AUTH
     * @param authToken       the AUTH token
     * @param overrides optional config overrides (may be null)
     */
    public PhotonSession(PhotonConnector connector, String userId, String authToken,
                         Map<String, Object> overrides) {
        this(connector, () -> new AuthCredentials(userId, authToken), overrides);
    }

    /**
     * Create a session with default config overrides.
     *
     * @param connector    the underlying connector
     * @param authProvider supplies the AUTH credentials
     */
    public PhotonSession(PhotonConnector connector, Supplier<AuthCredentials> authProvider) {
        this(connector, authProvider, null);
    }

    /**
     * Create a session with default config overrides and static auth credentials.
     *
     * @param connector  the underlying connector
     * @param userId     the user ID for AUTH
     * @param authToken  the AUTH token
     */
    public PhotonSession(PhotonConnector connector, String userId, String authToken) {
        this(connector, userId, authToken, null);
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
    public ResolvedConfig getConfig() {
        return config;
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

        connector.connect();
        try {
            AuthCredentials creds = authProvider.get();
            connector.authenticate(creds.getUserId(), creds.getToken());
            config = connector.configureAndStartReader(overrides);
            predicateEvaluator = PredicateEvaluator.compile(config.getRaw());
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
     * Route a request to the cache when a resolved CEL predicate matches.
     *
     * <p>A {@code null} return means no predicate matched and the caller should
     * send the request to the origin service. Connection and protocol errors
     * still propagate.
     *
     * @param request the request to send
     * @return the cache response, or null when Photon declines the request
     * @throws PhotonError if the session is not open
     */
    public PhotonResponse request(PhotonRequest request) {
        return request(request, new PhotonRequestCancellation());
    }

    PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
        if (state != State.OPEN) {
            throw new PhotonError("request() called on a " + state.name().toLowerCase() + " session");
        }
        if (!predicateEvaluator.shouldRouteToPhoton(request)) {
            return null;
        }
        return connector.request(request, cancellation);
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
        connector.close();
    }

    /**
     * Check if the session is open and ready for requests.
     */
    public boolean isOpen() {
        return state == State.OPEN;
    }
}
