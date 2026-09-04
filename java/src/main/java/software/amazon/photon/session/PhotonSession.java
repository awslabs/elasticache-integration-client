package software.amazon.photon.session;

import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.connection.PhotonRequestCancellation;
import software.amazon.photon.exception.PhotonCircuitOpenError;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;

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
 * Owns the active Photon connection and recovers it after transport failure.
 *
 * <p>A session orchestrates connection bring-up (transport connect, AUTH, and
 * the mandatory {@code photon.config} handshake) and exposes a synchronous
 * {@link #request(PhotonRequest)} method for the SDK transport to call.
 *
 * <p>Lifecycle: {@code CREATED} to {@code OPEN} to {@code CLOSED}.
 *
 * <p>Recovery is single-flight. Each bounded reconnect attempt creates a new
 * connection, resolves fresh authentication credentials, and repeats the
 * mandatory configuration handshake. Failed requests are not replayed because
 * the server may have processed them before the connection was lost.
 *
 * <p>Usage:
 * <pre>{@code
 * PhotonSession session = new PhotonSession(connection, authProvider, configOverrides);
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

    private final Supplier<PhotonConnection> connectionFactory;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> configOverrides;
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
    private volatile PhotonConnection connection;
    private volatile ResolvedConfig resolvedConfig;

    private CompletableFuture<PhotonConnection> recovery;
    private int consecutiveRecoveryFailures;
    private long circuitOpenUntilNanos;

    /**
     * Create a session with the given connection and auth provider.
     *
     * <p>A session constructed with a concrete connection cannot reconnect
     * because it has no connection factory.
     *
     * @param connection      the underlying connection (not yet connected)
     * @param authProvider    supplies fresh AUTH credentials
     * @param configOverrides optional config overrides (may be null)
     * @throws NullPointerException if connection or authProvider is null
     */
    public PhotonSession(PhotonConnection connection, Supplier<AuthCredentials> authProvider,
                         Map<String, Object> configOverrides) {
        this(singleConnectionFactory(connection), authProvider, configOverrides,
                ReconnectPolicy.builder().maxReconnectAttempts(0).build());
    }

    PhotonSession(Supplier<PhotonConnection> connectionFactory,
                  Supplier<AuthCredentials> authProvider,
                  Map<String, Object> configOverrides,
                  ReconnectPolicy reconnectPolicy) {
        if (connectionFactory == null) {
            throw new NullPointerException("connectionFactory");
        }
        if (authProvider == null) {
            throw new NullPointerException("authProvider");
        }
        this.connectionFactory = connectionFactory;
        this.authProvider = authProvider;
        this.configOverrides = configOverrides != null ? configOverrides : Collections.emptyMap();
        this.reconnectPolicy = reconnectPolicy != null ? reconnectPolicy : ReconnectPolicy.defaults();
    }

    /**
     * Create a session with the given connection and static auth credentials.
     *
     * @param connection      the underlying connection (not yet connected)
     * @param userId          the user ID for AUTH
     * @param authToken       the AUTH token
     * @param configOverrides optional config overrides (may be null)
     */
    public PhotonSession(PhotonConnection connection, String userId, String authToken,
                         Map<String, Object> configOverrides) {
        this(connection, () -> new AuthCredentials(userId, authToken), configOverrides);
    }

    /**
     * Create a session with default config overrides.
     *
     * @param connection   the underlying connection
     * @param authProvider supplies the AUTH credentials
     */
    public PhotonSession(PhotonConnection connection, Supplier<AuthCredentials> authProvider) {
        this(connection, authProvider, null);
    }

    /**
     * Create a session with default config overrides and static auth credentials.
     *
     * @param connection the underlying connection
     * @param userId     the user ID for AUTH
     * @param authToken  the AUTH token
     */
    public PhotonSession(PhotonConnection connection, String userId, String authToken) {
        this(connection, userId, authToken, null);
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
    public ResolvedConfig getResolvedConfig() {
        return resolvedConfig;
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

            connection = connectionFactory.get();
            try {
                resolvedConfig = initialize(connection);
                state = State.OPEN;
            } catch (RuntimeException failure) {
                state = State.CLOSED;
                connection.close();
                recoveryExecutor.shutdownNow();
                throw failure;
            }
        }
    }

    /**
     * Send a request to Photon.
     *
     * <p>A dead connection triggers single-flight recovery. A request that
     * discovers failure while in flight is not replayed.
     *
     * @param request the request to send
     * @return the Photon response
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

        PhotonConnection active = connection;
        if (active == null || active.isDead()) {
            active = recover(active);
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

        CompletableFuture<PhotonConnection> activeRecovery;
        synchronized (recoveryLock) {
            activeRecovery = recovery;
            recoveryLock.notifyAll();
        }
        if (activeRecovery != null) {
            activeRecovery.completeExceptionally(
                    new PhotonConnectionError("Session closed during recovery"));
        }
        recoveryExecutor.shutdownNow();

        if (connection != null) {
            connection.close();
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

    private ResolvedConfig initialize(PhotonConnection candidate) {
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
        return candidate.configureAndStartReader(configOverrides);
    }

    private PhotonConnection recover(PhotonConnection failedConnection) {
        CompletableFuture<PhotonConnection> flight;
        boolean owner = false;
        boolean halfOpenAttempt = false;

        synchronized (recoveryLock) {
            ensureOpen();
            if (connection != null
                    && connection != failedConnection
                    && connection.isReady()) {
                return connection;
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
            performRecovery(flight, failedConnection, halfOpenAttempt);
        }
        return awaitRecovery(flight);
    }

    private void recoverInBackground(PhotonConnection failedConnection) {
        try {
            recoveryExecutor.execute(() -> {
                try {
                    recover(failedConnection);
                } catch (PhotonError ignored) {
                    // The circuit state records the failure for subsequent requests.
                }
            });
        } catch (RejectedExecutionException ignored) {
            // close() may race with a transport failure.
        }
    }

    private void performRecovery(CompletableFuture<PhotonConnection> flight,
                                 PhotonConnection failedConnection,
                                 boolean halfOpenAttempt) {
        if (failedConnection != null) {
            failedConnection.close();
        }

        PhotonError lastFailure = new PhotonConnectionError("Photon reconnect attempts exhausted");
        PhotonConnection recovered = null;
        ResolvedConfig recoveredConfig = null;

        // Use a long counter so Integer.MAX_VALUE attempts remain bounded.
        for (long attempt = 1; attempt <= reconnectPolicy.getMaxReconnectAttempts(); attempt++) {
            if (state != State.OPEN) {
                lastFailure = new PhotonConnectionError("Session closed during recovery");
                break;
            }

            PhotonConnection candidate = null;
            try {
                candidate = connectionFactory.get();
                recoveredConfig = initialize(candidate);
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
                    connection = recovered;
                    resolvedConfig = recoveredConfig;
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
        // Double the delay cap for each prior failure, stopping at maxDelayMs.
        long cap = reconnectPolicy.getBaseDelayMs();
        for (int i = 1; i < failedAttempt && cap < reconnectPolicy.getMaxDelayMs(); i++) {
            // Saturate before multiplication can overflow.
            cap = Math.min(reconnectPolicy.getMaxDelayMs(), cap > Long.MAX_VALUE / 2
                    ? reconnectPolicy.getMaxDelayMs() : cap * 2);
        }
        // nextLong uses an exclusive bound, so Long.MAX_VALUE needs an unbounded draw.
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

    private PhotonConnection awaitRecovery(CompletableFuture<PhotonConnection> flight) {
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

    private static Supplier<PhotonConnection> singleConnectionFactory(
            PhotonConnection connection) {
        if (connection == null) {
            throw new NullPointerException("connection");
        }
        return new Supplier<PhotonConnection>() {
            private boolean supplied;

            @Override
            public synchronized PhotonConnection get() {
                if (supplied) {
                    throw new PhotonConnectionError(
                            "This session does not have a reconnectable connection factory");
                }
                supplied = true;
                return connection;
            }
        };
    }
}
