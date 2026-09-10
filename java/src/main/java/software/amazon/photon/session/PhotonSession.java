package software.amazon.photon.session;

import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnector;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonCircuitOpenError;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.predicate.PredicateEvaluator;

import java.io.Closeable;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Owns the active Photon connector and recovers it after transport failure.
 *
 * <p>A session orchestrates connection bring-up (transport connect, AUTH, and
 * the mandatory {@code photon.config} handshake) and exposes a synchronous
 * {@link #request(PhotonRequest)} method for the SDK transport to call.
 *
 * <p>Lifecycle: {@code CREATED} to {@code OPEN} to {@code CLOSED}.
 *
 * <p>Recovery is single-flight. Each bounded reconnect attempt creates a new
 * connector, resolves fresh authentication credentials, and repeats the
 * mandatory configuration handshake. Failed requests are not replayed because
 * the server may have processed them before the connection was lost.
 *
 * <p>Resolved CEL predicates determine which requests are sent to Photon.
 * Requests with no matching predicate are declined for origin pass-through.
 * Predicates are recompiled from each successfully resolved reconnect config.
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

    private static final AtomicInteger RECOVERY_THREAD_IDS = new AtomicInteger();

    /**
     * Session lifecycle state.
     */
    public enum State {
        /** Session created but not yet opened. */
        CREATED,
        /** Session is open and ready for requests. */
        OPEN,
        /** Session is closed and cannot be reopened. */
        CLOSED
    }

    /**
     * Recovery circuit state.
     */
    public enum CircuitState {
        /** Recovery attempts are allowed. */
        CLOSED,
        /** Recovery attempts are blocked until the configured open duration expires. */
        OPEN,
        /** One recovery probe is allowed to determine whether the circuit can close. */
        HALF_OPEN
    }

    private final Supplier<PhotonConnector> connectorFactory;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> overrides;
    private final ReconnectPolicy reconnectPolicy;
    private final ExecutorService recoveryExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable,
                "photon-recovery-" + RECOVERY_THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });
    private final Object lifecycleLock = new Object();
    private final Object recoveryLock = new Object();

    private volatile State state = State.CREATED;
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile PhotonConnector connector;
    private volatile ResolvedConfig config;
    private volatile PredicateEvaluator predicateEvaluator;

    private CompletableFuture<PhotonConnector> recovery;
    private int consecutiveRecoveryFailures;
    private long circuitOpenUntilNanos;

    /**
     * Create a session with the given connector and auth provider.
     *
     * <p>A session constructed with a concrete connector cannot reconnect
     * because it has no connector factory.
     *
     * @param connector    the underlying connector (not yet connected)
     * @param authProvider supplies fresh AUTH credentials
     * @param overrides    optional config overrides (may be null)
     * @throws NullPointerException if connector or authProvider is null
     */
    public PhotonSession(PhotonConnector connector, Supplier<AuthCredentials> authProvider,
                         Map<String, Object> overrides) {
        this(singleConnectorFactory(connector), authProvider, overrides,
                ReconnectPolicy.builder().maxReconnectAttempts(0).build());
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
     * Get the current session lifecycle state.
     *
     * @return the current lifecycle state
     */
    public State getState() {
        return state;
    }

    /**
     * Get the current recovery circuit state.
     *
     * @return the current circuit state
     */
    public CircuitState getCircuitState() {
        return circuitState;
    }

    /**
     * Get the resolved configuration from the config handshake.
     *
     * @return the resolved config, or null if the session has not opened
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
        synchronized (lifecycleLock) {
            if (state != State.CREATED) {
                throw new PhotonError("open() called on a " + state.name().toLowerCase() + " session");
            }

            connector = connectorFactory.get();
            try {
                ResolvedConfig initialConfig = initialize(connector);
                PredicateEvaluator initialPredicateEvaluator =
                        PredicateEvaluator.compile(initialConfig.getRaw());
                config = initialConfig;
                predicateEvaluator = initialPredicateEvaluator;
                state = State.OPEN;
            } catch (RuntimeException failure) {
                state = State.CLOSED;
                connector.close();
                recoveryExecutor.shutdownNow();
                throw failure;
            }
        }
    }

    /**
     * Route a request to Photon when a resolved CEL predicate matches.
     *
     * <p>A {@code null} return means no predicate matched and the caller should
     * send the request to the origin service. A dead connection triggers
     * single-flight recovery. A request that discovers failure while in flight
     * is not replayed.</p>
     *
     * @param request the request to send
     * @return the Photon response, or null when Photon declines the request
     * @throws PhotonError if the session is not open or Photon cannot serve the request
     */
    public PhotonResponse request(PhotonRequest request) {
        return request(request, new PhotonRequestCancellation());
    }

    PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
        ensureOpen();
        if (cancellation.isCancelled()) {
            throw new PhotonRequestCancellation.CancelledException();
        }

        PhotonConnector active = connector;
        PredicateEvaluator activePredicateEvaluator = predicateEvaluator;
        if (!activePredicateEvaluator.shouldRouteToPhoton(request)) {
            return null;
        }
        if (active == null || active.isDead()) {
            active = recover(active);
            PredicateEvaluator recoveredPredicateEvaluator = predicateEvaluator;
            if (recoveredPredicateEvaluator != activePredicateEvaluator
                    && !recoveredPredicateEvaluator.shouldRouteToPhoton(request)) {
                return null;
            }
        }

        try {
            return active.request(request, cancellation);
        } catch (PhotonError failure) {
            if (state == State.OPEN && active.isDead()) {
                recoverInBackground(active);
            }
            throw failure;
        }
    }

    /**
     * Close the session and interrupt any recovery backoff. Idempotent.
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
        }

        CompletableFuture<PhotonConnector> activeRecovery;
        synchronized (recoveryLock) {
            activeRecovery = recovery;
            recoveryLock.notifyAll();
        }
        if (activeRecovery != null) {
            activeRecovery.completeExceptionally(
                    new PhotonConnectionError("Session closed during recovery"));
        }
        recoveryExecutor.shutdownNow();

        if (connector != null) {
            connector.close();
        }
    }

    /**
     * Check whether the session is open and ready for requests.
     *
     * @return true when the lifecycle state is OPEN
     */
    public boolean isOpen() {
        return state == State.OPEN;
    }

    private ResolvedConfig initialize(PhotonConnector candidate) {
        candidate.connect();
        AuthCredentials credentials;
        try {
            credentials = authProvider.get();
        } catch (RuntimeException failure) {
            candidate.close();
            throw new PhotonConnectionError("Auth provider failed", failure);
        }
        if (credentials == null) {
            candidate.close();
            throw new PhotonConnectionError("Auth provider returned null credentials");
        }
        candidate.authenticate(credentials.getUserId(), credentials.getToken());
        return candidate.configureAndStartReader(overrides);
    }

    private PhotonConnector recover(PhotonConnector failedConnector) {
        CompletableFuture<PhotonConnector> flight;
        boolean owner = false;
        boolean halfOpenAttempt = false;

        synchronized (recoveryLock) {
            ensureOpen();
            if (connector != null
                    && connector != failedConnector
                    && connector.isReady()) {
                return connector;
            }
            if (recovery != null) {
                flight = recovery;
            } else {
                long now = System.nanoTime();
                if (circuitState == CircuitState.OPEN) {
                    if (now < circuitOpenUntilNanos) {
                        throw new PhotonCircuitOpenError("Photon recovery circuit is open");
                    }
                    circuitState = CircuitState.HALF_OPEN;
                    halfOpenAttempt = true;
                }
                flight = new CompletableFuture<>();
                recovery = flight;
                owner = true;
            }
        }

        if (owner) {
            performRecovery(flight, failedConnector, halfOpenAttempt);
        }
        return awaitRecovery(flight);
    }

    private void recoverInBackground(PhotonConnector failedConnector) {
        try {
            recoveryExecutor.execute(() -> {
                try {
                    recover(failedConnector);
                } catch (PhotonError ignored) {
                    // The circuit state records the failure for subsequent requests.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close() may race with a transport failure.
        }
    }

    /**
     * Own the shared recovery flight, retry connection initialization, and
     * publish either the recovered connector or the terminal failure.
     *
     * @param flight the future shared by callers waiting for this recovery
     * @param failedConnector the connector being replaced, if present
     * @param halfOpenAttempt true when this recovery is the circuit's probe
     */
    private void performRecovery(CompletableFuture<PhotonConnector> flight,
                                 PhotonConnector failedConnector,
                                 boolean halfOpenAttempt) {
        if (failedConnector != null) {
            failedConnector.close();
        }

        PhotonError lastFailure = new PhotonConnectionError("Photon reconnect attempts exhausted");
        PhotonConnector recovered = null;
        ResolvedConfig recoveredConfig = null;
        PredicateEvaluator recoveredPredicateEvaluator = null;

        // Use a long counter so Integer.MAX_VALUE attempts remain bounded.
        for (long attempt = 1; attempt <= reconnectPolicy.getMaxReconnectAttempts(); attempt++) {
            if (state != State.OPEN) {
                lastFailure = new PhotonConnectionError("Session closed during recovery");
                break;
            }

            PhotonConnector candidate = null;
            try {
                candidate = connectorFactory.get();
                recoveredConfig = initialize(candidate);
                recoveredPredicateEvaluator =
                        PredicateEvaluator.compile(recoveredConfig.getRaw());
                recovered = candidate;
                break;
            } catch (RuntimeException failure) {
                if (candidate != null) {
                    candidate.close();
                }
                lastFailure = failure instanceof PhotonError
                        ? (PhotonError) failure
                        : new PhotonConnectionError("Photon reconnect failed", failure);
                if (attempt < reconnectPolicy.getMaxReconnectAttempts()
                        && !waitBeforeRetry(attempt)) {
                    lastFailure = new PhotonConnectionError("Session closed during recovery");
                    break;
                }
            }
        }

        synchronized (recoveryLock) {
            try {
                if (recovered != null && state == State.OPEN) {
                    config = recoveredConfig;
                    predicateEvaluator = recoveredPredicateEvaluator;
                    // Publish the connector last so readers see its matching config.
                    connector = recovered;
                    consecutiveRecoveryFailures = 0;
                    circuitState = CircuitState.CLOSED;
                    circuitOpenUntilNanos = 0;
                    flight.complete(recovered);
                } else {
                    if (recovered != null) {
                        recovered.close();
                    }
                    consecutiveRecoveryFailures++;
                    if (halfOpenAttempt
                            || consecutiveRecoveryFailures
                            >= reconnectPolicy.getCircuitFailureThreshold()) {
                        circuitState = CircuitState.OPEN;
                        circuitOpenUntilNanos = System.nanoTime()
                                + TimeUnit.MILLISECONDS.toNanos(
                                reconnectPolicy.getCircuitOpenDurationMs());
                    } else {
                        circuitState = CircuitState.CLOSED;
                    }
                    flight.completeExceptionally(lastFailure);
                }
            } finally {
                if (recovery == flight) {
                    recovery = null;
                }
                recoveryLock.notifyAll();
            }
        }
    }

    /**
     * Wait using capped exponential full jitter after a failed reconnect.
     *
     * @param failedAttempt the one-based reconnect attempt that just failed
     * @return true if the session remains open and another attempt may proceed
     */
    private boolean waitBeforeRetry(long failedAttempt) {
        long cap = reconnectPolicy.getBaseDelayMs();
        for (int i = 1; i < failedAttempt && cap < reconnectPolicy.getMaxDelayMs(); i++) {
            // Guard the doubling operation itself from overflowing long.
            long doubledCap = cap > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : cap * 2;
            // Never let exponential growth exceed the configured maximum delay.
            cap = Math.min(reconnectPolicy.getMaxDelayMs(), doubledCap);
        }
        // nextLong(bound) cannot express an inclusive Long.MAX_VALUE upper limit.
        long delay = cap == Long.MAX_VALUE
                ? ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE
                : ThreadLocalRandom.current().nextLong(cap + 1);
        if (delay == 0) {
            return state == State.OPEN;
        }
        synchronized (recoveryLock) {
            if (state != State.OPEN) {
                return false;
            }
            try {
                recoveryLock.wait(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return state == State.OPEN;
        }
    }

    private PhotonConnector awaitRecovery(CompletableFuture<PhotonConnector> flight) {
        try {
            return flight.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotonConnectionError("Interrupted while waiting for Photon recovery", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PhotonError) {
                throw (PhotonError) cause;
            }
            throw new PhotonConnectionError("Photon recovery failed", cause);
        }
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new PhotonError(
                    "request() called on a " + state.name().toLowerCase() + " session");
        }
    }

    private static Supplier<PhotonConnector> singleConnectorFactory(
            PhotonConnector connector) {
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
}
