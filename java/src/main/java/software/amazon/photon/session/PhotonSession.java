package software.amazon.photon.session;

import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonCacheUnavailable;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.predicate.PredicateEvaluator;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.protocol.SerializedCommand;
import software.amazon.photon.resp.RespNull;
import software.amazon.photon.resp.RespValue;

import java.io.Closeable;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the active Photon connector and replaces it after transport failure.
 *
 * <p>Initial connection is bounded by {@link ReconnectPolicy#getConnectAttempts()}
 * and {@link ReconnectPolicy#getConnectionTimeoutMs()}. Once open, an idle or
 * in-flight disconnect starts one background reconnect loop. The loop obtains
 * fresh credentials, repeats {@code AUTH} and {@code photon.config}, and keeps
 * trying until it succeeds or the session closes.
 *
 * <p>Requests are never replayed after being admitted to Photon. While no
 * connector is available, matching requests use origin fallback until the
 * configured fallback duration expires.
 *
 * <p>Resolved CEL predicates determine which requests are sent to Photon.
 * Requests with no matching predicate are declined for origin pass-through.
 * The refresher started by {@link #open()} fetches and applies resolved config
 * updates while a connector is open. Refresh failures keep the last-known-good
 * config; failures that kill the connector use the normal reconnect path.
 */
public class PhotonSession implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(PhotonSession.class.getName());
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    public enum State {
        /** Created but not opened. */
        CREATED,
        /** A configured connector is available. */
        OPEN,
        /** No connector is available and background recovery is active. */
        RECONNECTING,
        /** Terminal state. */
        CLOSED
    }

    private final Supplier<PhotonConnector> connectorFactory;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> overrides;
    private final ReconnectPolicy reconnectPolicy;
    private final ExecutorService reconnectExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable,
                "photon-reconnect-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Object stateLock = new Object();

    private volatile State state = State.CREATED;
    private volatile PhotonConnector connector;
    private volatile PhotonConnector candidate;
    private volatile ConnectionSnapshot snapshot;
    private volatile ConfigRefresher refresher;
    private volatile long fallbackDeadlineNanos;
    private boolean opening;
    private boolean reconnectRunning;
    private boolean overridesSent;

    /**
     * Create a session around one concrete connector.
     *
     * <p>This compatibility constructor cannot create replacement connectors.
     */
    public PhotonSession(PhotonConnector connector, Supplier<AuthCredentials> authProvider,
                         Map<String, Object> overrides) {
        this(singleConnectorFactory(connector), authProvider, overrides,
                ReconnectPolicy.builder().connectAttempts(1).build());
    }

    PhotonSession(Supplier<PhotonConnector> connectorFactory,
                  Supplier<AuthCredentials> authProvider,
                  Map<String, Object> overrides,
                  ReconnectPolicy reconnectPolicy) {
        if (connectorFactory == null) {
            throw new NullPointerException("connectorFactory");
        }
        if (authProvider == null) {
            throw new NullPointerException("authProvider");
        }
        this.connectorFactory = connectorFactory;
        this.authProvider = authProvider;
        this.overrides = overrides != null ? overrides : Collections.emptyMap();
        this.reconnectPolicy = reconnectPolicy != null ? reconnectPolicy : ReconnectPolicy.defaults();
    }

    /** Create a concrete-connector session with static credentials. */
    public PhotonSession(PhotonConnector connector, String userId, String authToken,
                         Map<String, Object> overrides) {
        this(connector, () -> new AuthCredentials(userId, authToken), overrides);
    }

    /** Create a concrete-connector session with no config overrides. */
    public PhotonSession(PhotonConnector connector, Supplier<AuthCredentials> authProvider) {
        this(connector, authProvider, null);
    }

    /** Create a concrete-connector session with static credentials and no overrides. */
    public PhotonSession(PhotonConnector connector, String userId, String authToken) {
        this(connector, userId, authToken, null);
    }

    /** Return the current lifecycle state. */
    public State getState() {
        return state;
    }

    /** Return the latest successfully resolved Photon configuration. */
    public ResolvedConfig getConfig() {
        return snapshot != null ? snapshot.config : null;
    }

    /**
     * For testing: force the "overrides not yet acknowledged" state that production code cannot
     * currently reach after a successful open (there is no code path that changes the overrides
     * after construction).
     */
    void setOverridesSent(boolean overridesSent) {
        synchronized (stateLock) {
            this.overridesSent = overridesSent;
        }
    }

    /** For testing: whether the overrides have been acknowledged by the server. */
    boolean isOverridesSent() {
        synchronized (stateLock) {
            return overridesSent;
        }
    }

    /** For testing: the refresher started by {@link #open()}, or null before it starts. */
    ConfigRefresher refresher() {
        return refresher;
    }

    /**
     * Open the session: connect, authenticate, perform the config handshake,
     * compile the resolved predicates, then start the config refresher.
     *
     * <p>Connect/authenticate/config failures are retried with a new connector and fresh
     * credentials. Once a connector is live, refresher construction and startup are local session
     * setup rather than connectivity, so a failure there closes the connector and fails
     * immediately instead of repeating a successful handshake. The session becomes {@code OPEN}
     * only once the refresher has started.
     *
     * <p>Lifecycle transitions are synchronized against {@link #close()}, but blocking connection
     * and credential work runs outside the state lock. A concurrent close can therefore tear down
     * an in-flight open; the opener checks that it still owns the lifecycle before publishing the
     * config or starting the refresher.
     */
    public void open() {
        synchronized (stateLock) {
            if (state != State.CREATED) {
                throw new PhotonError("open() called on a " + state.name().toLowerCase() + " session");
            }
            if (opening) {
                throw new PhotonError("open() called while session is opening");
            }
            opening = true;
        }

        try {
            RuntimeException lastFailure = null;
            PhotonConnector established = null;
            ConnectionSnapshot establishedSnapshot = null;
            for (long attempt = 1; attempt <= reconnectPolicy.getConnectAttempts(); attempt++) {
                PhotonConnector next = null;
                try {
                    next = connectorFactory.get();
                    synchronized (stateLock) {
                        if (state != State.CREATED) {
                            next.close();
                            throw new PhotonConnectionError("Session closed while opening");
                        }
                        candidate = next;
                    }
                    ConnectionSnapshot snapshot = initializeWithTimeout(next);
                    synchronized (stateLock) {
                        if (state != State.CREATED || !next.isReady()) {
                            next.close();
                            throw new PhotonConnectionError(
                                    state == State.CREATED
                                            ? "Photon connection closed while opening"
                                            : "Session closed while opening");
                        }
                    }
                    established = next;
                    establishedSnapshot = snapshot;
                    break;
                } catch (RuntimeException failure) {
                    lastFailure = failure;
                    synchronized (stateLock) {
                        if (candidate == next) {
                            candidate = null;
                        }
                    }
                    if (next != null) {
                        next.close();
                    }
                    if (attempt < reconnectPolicy.getConnectAttempts()) {
                        sleep(reconnectPolicy.delayFor(attempt));
                    }
                }
            }

            if (established == null || establishedSnapshot == null) {
                synchronized (stateLock) {
                    state = State.CLOSED;
                }
                reconnectExecutor.shutdownNow();
                throw lastFailure != null
                        ? lastFailure
                        : new PhotonConnectionError("Photon initial connection failed");
            }

            ConfigRefresher nextRefresher = null;
            try {
                nextRefresher = createRefresher(
                        establishedSnapshot.config.getRefreshInterval(), this::refreshOnce);
                synchronized (stateLock) {
                    if (state != State.CREATED || !established.isReady()) {
                        throw new PhotonConnectionError(
                                state == State.CREATED
                                        ? "Photon connection closed while opening"
                                        : "Session closed while opening");
                    }
                    nextRefresher.start();
                    candidate = null;
                    publish(established, establishedSnapshot);
                    refresher = nextRefresher;
                    state = State.OPEN;
                }
            } catch (RuntimeException failure) {
                synchronized (stateLock) {
                    if (candidate == established) {
                        candidate = null;
                    }
                }
                if (nextRefresher != null) {
                    nextRefresher.stop();
                }
                established.close();
                synchronized (stateLock) {
                    state = State.CLOSED;
                }
                reconnectExecutor.shutdownNow();
                throw failure;
            }
        } finally {
            synchronized (stateLock) {
                opening = false;
            }
        }
    }

    /**
     * Construct the refresher for this session. Package-private for testing: tests override this
     * to observe refresher lifecycle without a public constructor or getter.
     */
    ConfigRefresher createRefresher(double intervalSeconds, Runnable refreshOnce) {
        return new ConfigRefresher(intervalSeconds, refreshOnce);
    }

    /**
     * The refresher's periodic callback: send one {@code photon.config} refresh and apply the
     * result.
     *
     * <p>Sends the overrides if they have not yet been acknowledged by the server, otherwise
     * sends the no-argument form ("my overrides are unchanged"). A RESP3 null reply means the
     * server's config has not changed, so nothing is applied or rescheduled. Any other reply is
     * parsed, its predicates compiled, and installed as a single immutable snapshot; the
     * refresher is then rescheduled on the interval the new config carries.
     *
     * <p>A {@link PhotonError} from either step is caught here so it cannot escape the refresher
     * thread and silently stop every future refresh: the current config and schedule are left in
     * place and the next tick retries. If the failure leaves the connector dead, the existing
     * background reconnect path replaces it. Replies from a connector that has since been closed
     * or replaced are discarded.
     */
    void refreshOnce() {
        PhotonConnector active;
        ConfigRefresher activeRefresher;
        Map<String, Object> payload;
        synchronized (stateLock) {
            if (state != State.OPEN || connector == null) {
                return;
            }
            active = connector;
            activeRefresher = refresher;
            payload = overridesSent ? null : overrides;
        }

        SerializedCommand command = PhotonProtocol.serializeConfig(payload);
        RespValue reply;
        try {
            reply = active.execute(command.getCommandName(),
                    command.getArgs().toArray(new byte[0][]));
        } catch (PhotonError e) {
            handleRefreshFailure(active, e);
            return;
        }

        if (reply instanceof RespNull) {
            return;
        }

        ResolvedConfig newConfig;
        PredicateEvaluator newPredicates;
        try {
            newConfig = PhotonProtocol.parseConfigResponse(reply);
            newPredicates = PredicateEvaluator.compile(newConfig.getRaw());
        } catch (PhotonError e) {
            handleRefreshFailure(active, e);
            return;
        }

        synchronized (stateLock) {
            if (state != State.OPEN || connector != active || refresher != activeRefresher) {
                // The session closed or reconnected while this refresh was in flight.
                return;
            }
            activeRefresher.setInterval(newConfig.getRefreshInterval());
            overridesSent = true;
            snapshot = new ConnectionSnapshot(newConfig, newPredicates);
        }
    }

    /**
     * Log a refresh failure unless the connector is already being replaced, and start recovery
     * if the failure made the active connector unusable.
     */
    private void handleRefreshFailure(PhotonConnector active, PhotonError e) {
        if (state == State.OPEN && connector == active) {
            LOGGER.log(Level.WARNING, "Config refresh failed; keeping the current config", e);
        }
        if (active.isDead()) {
            beginReconnect(active);
        }
    }

    /**
     * Route a request through Photon when eligible.
     *
     * @return a Photon response, or {@code null} for signed origin fallback
     */
    public PhotonResponse request(PhotonRequest request) {
        return request(request, new PhotonRequestCancellation());
    }

    PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
        ensureUsable();
        if (cancellation.isCancelled()) {
            throw new PhotonRequestCancellation.CancelledException();
        }
        ConnectionSnapshot currentSnapshot = snapshot;
        PredicateEvaluator evaluator =
                currentSnapshot != null ? currentSnapshot.predicateEvaluator : null;
        if (evaluator != null && !evaluator.shouldRouteToPhoton(request)) {
            return null;
        }

        PhotonConnector active = connector;
        if (active == null || !active.isReady()) {
            beginReconnect(active);
            if (fallbackAllowed()) {
                return null;
            }
            throw new PhotonCacheUnavailable(
                    "Photon has no cache connection and origin fallback is no longer allowed");
        }

        try {
            return active.request(request, cancellation);
        } catch (PhotonError failure) {
            if (active.isDead()) {
                beginReconnect(active);
            }
            throw failure;
        }
    }

    /**
     * Stop the refresher, then close the connector. Idempotent.
     *
     * <p>The refresher is stopped strictly before the connector closes, so a refresh can never
     * fire into a half-closed connection during an orderly shutdown. The state transition is
     * synchronized against {@link #open()}, so an in-flight opener cannot publish or start a
     * refresher after shutdown wins the lifecycle.
     *
     * <p>Marking the session closed and capturing the refresher happen under the lock; joining
     * the refresher thread and closing the connector happen after the lock is released. Holding
     * the lock across the join would risk a stall if refresh publication ever needed it too.
     */
    @Override
    public void close() {
        PhotonConnector active;
        PhotonConnector openingConnector;
        ConfigRefresher activeRefresher;
        synchronized (stateLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            active = connector;
            openingConnector = candidate;
            connector = null;
            candidate = null;
            activeRefresher = refresher;
            refresher = null;
            stateLock.notifyAll();
        }
        if (activeRefresher != null) {
            activeRefresher.stop();
        }
        if (openingConnector != null && openingConnector != active) {
            openingConnector.close();
        }
        if (active != null) {
            active.close();
        }
        reconnectExecutor.shutdownNow();
    }

    /** Return whether requests may use this session. */
    public boolean isOpen() {
        State current = state;
        return current == State.OPEN || current == State.RECONNECTING;
    }

    private ConnectionSnapshot initializeWithTimeout(PhotonConnector next) {
        FutureTask<ConnectionSnapshot> task = new FutureTask<>(() -> initialize(next));
        Thread thread = new Thread(task, "photon-connect-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(reconnectPolicy.getConnectionTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            next.close();
            task.cancel(true);
            throw new PhotonConnectionError(
                    "Opening Photon connection timed out after "
                            + reconnectPolicy.getConnectionTimeoutMs() + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            next.close();
            throw new PhotonConnectionError("Interrupted while opening Photon connection", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new PhotonConnectionError("Photon connection initialization failed", cause);
        }
    }

    private ConnectionSnapshot initialize(PhotonConnector next) {
        next.setOnDisconnect(() -> beginReconnect(next));
        next.connect();
        AuthCredentials credentials;
        try {
            credentials = authProvider.get();
        } catch (RuntimeException failure) {
            throw new PhotonConnectionError("Auth provider failed", failure);
        }
        if (credentials == null) {
            throw new PhotonConnectionError("Auth provider returned null credentials");
        }
        next.authenticate(credentials.getUserId(), credentials.getToken());
        ResolvedConfig nextConfig = next.configureAndStartReader(overrides);
        return new ConnectionSnapshot(
                nextConfig, PredicateEvaluator.compile(nextConfig.getRaw()));
    }

    private void beginReconnect(PhotonConnector failed) {
        synchronized (stateLock) {
            if (state == State.CLOSED || state == State.CREATED) {
                return;
            }
            if (state == State.OPEN) {
                PhotonConnector current = connector;
                if (current != null && current.isReady() && current != failed) {
                    return;
                }
                fallbackDeadlineNanos = fallbackDeadline();
                connector = null;
                state = State.RECONNECTING;
                if (current != null) {
                    current.close();
                }
            }
            if (!reconnectRunning) {
                reconnectRunning = true;
                try {
                    reconnectExecutor.execute(this::reconnectForever);
                } catch (RejectedExecutionException ignored) {
                    reconnectRunning = false;
                }
            }
        }
    }

    private void reconnectForever() {
        long attempt = 0;
        try {
            while (state == State.RECONNECTING) {
                attempt++;
                PhotonConnector next = null;
                try {
                    next = connectorFactory.get();
                    synchronized (stateLock) {
                        if (state == State.CLOSED) {
                            next.close();
                            return;
                        }
                        candidate = next;
                    }
                    ConnectionSnapshot snapshot = initializeWithTimeout(next);
                    synchronized (stateLock) {
                        candidate = null;
                        if (state == State.CLOSED || !next.isReady()) {
                            next.close();
                            if (state == State.CLOSED) {
                                return;
                            }
                            throw new PhotonConnectionError(
                                    "Photon reconnect candidate closed before publication");
                        }
                        publish(next, snapshot);
                        state = State.OPEN;
                    }
                    return;
                } catch (RuntimeException failure) {
                    synchronized (stateLock) {
                        if (candidate == next) {
                            candidate = null;
                        }
                    }
                    if (next != null) {
                        next.close();
                    }
                    if (!waitForRetry(reconnectPolicy.delayFor(attempt))) {
                        return;
                    }
                }
            }
        } finally {
            synchronized (stateLock) {
                reconnectRunning = false;
                if (state == State.RECONNECTING) {
                    beginReconnect(null);
                }
            }
        }
    }

    private void publish(PhotonConnector next, ConnectionSnapshot snapshot) {
        ConfigRefresher activeRefresher = refresher;
        if (activeRefresher != null) {
            activeRefresher.setInterval(snapshot.config.getRefreshInterval());
        }
        overridesSent = true;
        this.snapshot = snapshot;
        connector = next;
    }

    private boolean fallbackAllowed() {
        long deadline = fallbackDeadlineNanos;
        return deadline == Long.MAX_VALUE || System.nanoTime() < deadline;
    }

    private long fallbackDeadline() {
        long seconds = reconnectPolicy.getFallbackDurationSeconds();
        if (seconds < 0) {
            return Long.MAX_VALUE;
        }
        long duration = TimeUnit.SECONDS.toNanos(seconds);
        long now = System.nanoTime();
        return duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
    }

    private boolean waitForRetry(long delayMs) {
        synchronized (stateLock) {
            if (state != State.RECONNECTING) {
                return false;
            }
            try {
                stateLock.wait(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return state == State.RECONNECTING;
        }
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotonConnectionError("Interrupted during Photon connection backoff", e);
        }
    }

    private void ensureUsable() {
        State current = state;
        if (current != State.OPEN && current != State.RECONNECTING) {
            throw new PhotonError(
                    "request() called on a " + current.name().toLowerCase() + " session");
        }
    }

    private static Supplier<PhotonConnector> singleConnectorFactory(PhotonConnector connector) {
        if (connector == null) {
            throw new NullPointerException("connector");
        }
        return new Supplier<PhotonConnector>() {
            private boolean supplied;

            @Override
            public synchronized PhotonConnector get() {
                if (supplied) {
                    throw new PhotonConnectionError(
                            "This session does not have a reconnectable connector factory");
                }
                supplied = true;
                return connector;
            }
        };
    }

    /** Immutable pairing of a resolved config with its compiled predicates. */
    private static final class ConnectionSnapshot {
        private final ResolvedConfig config;
        private final PredicateEvaluator predicateEvaluator;

        private ConnectionSnapshot(ResolvedConfig config,
                                   PredicateEvaluator predicateEvaluator) {
            this.config = config;
            this.predicateEvaluator = predicateEvaluator;
        }
    }
}
