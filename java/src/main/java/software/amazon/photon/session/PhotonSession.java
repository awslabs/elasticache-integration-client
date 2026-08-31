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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Owns the active Photon connection and recovers it after transport failure.
 *
 * <p>Recovery is single-flight. Each bounded reconnect attempt creates a new
 * connection, resolves fresh authentication credentials, and repeats the
 * mandatory configuration handshake. Failed requests are not replayed because
 * the server may have processed them before the connection was lost.
 */
public class PhotonSession implements Closeable {

    public enum State {
        CREATED,
        OPEN,
        CLOSED
    }

    public enum CircuitState {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final Supplier<PhotonConnection> connectionFactory;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> configOverrides;
    private final ReconnectPolicy reconnectPolicy;
    private final Object lifecycleLock = new Object();
    private final Object recoveryLock = new Object();

    private volatile State state = State.CREATED;
    private volatile CircuitState circuitState = CircuitState.CLOSED;
    private volatile PhotonConnection connection;
    private volatile ResolvedConfig resolvedConfig;

    private CompletableFuture<PhotonConnection> recovery;
    private int consecutiveRecoveryFailures;
    private long circuitOpenUntilNanos;

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

    public PhotonSession(PhotonConnection connection, String userId, String authToken,
                         Map<String, Object> configOverrides) {
        this(connection, () -> new AuthCredentials(userId, authToken), configOverrides);
    }

    public PhotonSession(PhotonConnection connection, Supplier<AuthCredentials> authProvider) {
        this(connection, authProvider, null);
    }

    public PhotonSession(PhotonConnection connection, String userId, String authToken) {
        this(connection, userId, authToken, null);
    }

    public State getState() {
        return state;
    }

    public CircuitState getCircuitState() {
        return circuitState;
    }

    public ResolvedConfig getResolvedConfig() {
        return resolvedConfig;
    }

    public void open() {
        synchronized (lifecycleLock) {
            if (state != State.CREATED) {
                throw new PhotonError("open() called on a " + state.name().toLowerCase() + " session");
            }

            PhotonConnection initial = connectionFactory.get();
            connection = initial;
            try {
                resolvedConfig = initialize(initial);
                state = State.OPEN;
            } catch (RuntimeException failure) {
                state = State.CLOSED;
                initial.close();
                throw failure;
            }
        }
    }

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
                try {
                    recover(active);
                } catch (PhotonError recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
            }
            throw failure;
        }
    }

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

        PhotonConnection active = connection;
        if (active != null) {
            active.close();
        }
    }

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
            PhotonConnection current = connection;
            if (current != null && current != failedConnection && current.isReady()) {
                return current;
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

    private void performRecovery(CompletableFuture<PhotonConnection> flight,
                                 PhotonConnection failedConnection,
                                 boolean halfOpenAttempt) {
        if (failedConnection != null) {
            failedConnection.close();
        }

        PhotonError lastFailure = new PhotonConnectionError("Photon reconnect attempts exhausted");
        PhotonConnection recovered = null;
        ResolvedConfig recoveredConfig = null;

        for (int attempt = 1; attempt <= reconnectPolicy.getMaxReconnectAttempts(); attempt++) {
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

    private boolean waitBeforeRetry(int failedAttempt) {
        long cap = reconnectPolicy.getBaseDelayMs();
        for (int i = 1; i < failedAttempt && cap < reconnectPolicy.getMaxDelayMs(); i++) {
            cap = Math.min(reconnectPolicy.getMaxDelayMs(), cap > Long.MAX_VALUE / 2
                    ? reconnectPolicy.getMaxDelayMs() : cap * 2);
        }
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
