package software.amazon.elasticache.integrations.session;

import java.io.Closeable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsConnector;
import software.amazon.elasticache.integrations.connection.ElastiCacheIntegrationsRequestCancellation;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsAuthError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsCacheUnavailable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigUnevaluable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.predicate.PredicateEvaluator;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.protocol.SerializedCommand;
import software.amazon.elasticache.integrations.resp.RespNull;
import software.amazon.elasticache.integrations.resp.RespValue;

/**
 * Owns the active Cache connector and replaces it after transport failure.
 *
 * <p>Initial connection retries until the reconnect policy's connection timeout has passed. Once
 * open, an idle or in-flight disconnect starts one background reconnect loop. The loop obtains
 * fresh credentials, repeats {@code HELLO 3 AUTH} and {@code aws.config}, and keeps trying until it
 * succeeds or the session closes.
 *
 * <p>While no connector is available, a matching request waits up to the connection timeout for the
 * reconnect. A replay-safe request may then use origin fallback until the configured fallback
 * duration expires; a routed write fails without contacting origin. After a request is admitted to
 * Cache, the HTTP adapters allow direct origin fallback only when the connection drops and the
 * operation is in the shared replay-safe catalog.
 *
 * <p>Resolved CEL predicates determine which requests are sent to Cache. Requests with no matching
 * predicate are declined for origin pass-through. The background runners started by {@link #open()}
 * refresh authentication and fetch resolved config updates while a connector is open. A config
 * refresh failure keeps the last-known-good config; failures that kill the connector use the normal
 * reconnect path. Once the staleness window passes with no config confirmed by the server, the
 * config is no longer trusted: the connector is dropped so a new one re-resolves it, and requests
 * fall back meanwhile.
 */
class ElastiCacheIntegrationsSession implements Closeable {

    private static final Logger LOGGER =
            Logger.getLogger(ElastiCacheIntegrationsSession.class.getName());
    private static final String AUTH_ERROR = "AUTH_ERROR";
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    /** Default staleness window: one hour. */
    public static final long DEFAULT_STALENESS_DURATION_SECONDS = 3600;

    /** Session lifecycle state. */
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

    private final Supplier<ElastiCacheIntegrationsConnector> connectorFactory;
    private final Supplier<AuthCredentials> authProvider;
    private final Map<String, Object> overrides;
    private final ReconnectPolicy reconnectPolicy;
    private final long stalenessDurationNanos;
    private final LongSupplier nanoClock;
    private final ExecutorService reconnectExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "aws-elasticache-integration-client-reconnect-" + THREAD_IDS.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    });
    private final Object stateLock = new Object();

    private volatile State state = State.CREATED;
    private volatile ElastiCacheIntegrationsConnector connector;
    private volatile ElastiCacheIntegrationsConnector candidate;
    private volatile ConnectionSnapshot snapshot;
    private volatile Refresher configRefresher;
    private volatile Refresher reauthRefresher;
    private boolean canRetry = true;
    private volatile long readyDeadlineNanos;
    private volatile long fallbackDeadlineNanos;
    private boolean opening;
    private boolean reconnectRunning;
    private boolean overridesSent;

    /** When the config stops being trusted; restarted by every confirmation from the server. */
    private long configDeadlineNanos;

    /**
     * Create a session around one concrete connector.
     *
     * <p>This compatibility constructor cannot create replacement connectors.
     *
     * @param connector the connector to open
     * @param authProvider supplies AUTH credentials for each handshake
     * @param overrides the {@code aws.config} overrides, or {@code null} for none
     */
    public ElastiCacheIntegrationsSession(
            ElastiCacheIntegrationsConnector connector,
            Supplier<AuthCredentials> authProvider,
            Map<String, Object> overrides) {
        this(singleConnectorFactory(connector), authProvider, overrides, ReconnectPolicy.defaults());
        // A session around one concrete connector cannot build another, so it tries only once.
        this.canRetry = false;
    }

    ElastiCacheIntegrationsSession(
            Supplier<ElastiCacheIntegrationsConnector> connectorFactory,
            Supplier<AuthCredentials> authProvider,
            Map<String, Object> overrides,
            ReconnectPolicy reconnectPolicy) {
        this(
                connectorFactory,
                authProvider,
                overrides,
                reconnectPolicy,
                DEFAULT_STALENESS_DURATION_SECONDS);
    }

    ElastiCacheIntegrationsSession(
            Supplier<ElastiCacheIntegrationsConnector> connectorFactory,
            Supplier<AuthCredentials> authProvider,
            Map<String, Object> overrides,
            ReconnectPolicy reconnectPolicy,
            long stalenessDurationSeconds) {
        this(
                connectorFactory,
                authProvider,
                overrides,
                reconnectPolicy,
                stalenessDurationSeconds,
                System::nanoTime);
    }

    /**
     * Test constructor: {@code nanoClock} replaces {@link System#nanoTime()} for staleness and outage
     * deadlines.
     */
    ElastiCacheIntegrationsSession(
            Supplier<ElastiCacheIntegrationsConnector> connectorFactory,
            Supplier<AuthCredentials> authProvider,
            Map<String, Object> overrides,
            ReconnectPolicy reconnectPolicy,
            long stalenessDurationSeconds,
            LongSupplier nanoClock) {
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
        if (stalenessDurationSeconds <= 0
                || stalenessDurationSeconds > TimeUnit.NANOSECONDS.toSeconds(Long.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "stalenessDurationSeconds must be a positive whole number of seconds: "
                            + stalenessDurationSeconds);
        }
        this.stalenessDurationNanos = TimeUnit.SECONDS.toNanos(stalenessDurationSeconds);
        this.nanoClock = nanoClock != null ? nanoClock : System::nanoTime;
    }

    /**
     * Create a concrete-connector session with static credentials.
     *
     * @param connector the connector to open
     * @param userId the user ID for AUTH
     * @param authToken the password or token for AUTH
     * @param overrides the {@code aws.config} overrides, or {@code null} for none
     */
    public ElastiCacheIntegrationsSession(
            ElastiCacheIntegrationsConnector connector,
            String userId,
            String authToken,
            Map<String, Object> overrides) {
        this(connector, () -> new AuthCredentials(userId, authToken), overrides);
    }

    /**
     * Create a concrete-connector session with no config overrides.
     *
     * @param connector the connector to open
     * @param authProvider supplies AUTH credentials for each handshake
     */
    public ElastiCacheIntegrationsSession(
            ElastiCacheIntegrationsConnector connector, Supplier<AuthCredentials> authProvider) {
        this(connector, authProvider, null);
    }

    /**
     * Create a concrete-connector session with static credentials and no overrides.
     *
     * @param connector the connector to open
     * @param userId the user ID for AUTH
     * @param authToken the password or token for AUTH
     */
    public ElastiCacheIntegrationsSession(
            ElastiCacheIntegrationsConnector connector, String userId, String authToken) {
        this(connector, userId, authToken, null);
    }

    /**
     * Return the current lifecycle state.
     *
     * @return the current state
     */
    public State getState() {
        return state;
    }

    /** For testing: the staleness window this session was created with, in seconds. */
    long stalenessDurationSeconds() {
        return TimeUnit.NANOSECONDS.toSeconds(stalenessDurationNanos);
    }

    /** For testing: the reconnect policy this session was created with. */
    ReconnectPolicy reconnectPolicy() {
        return reconnectPolicy;
    }

    /** For testing: when requests stop waiting for the current outage's reconnect. */
    long readyDeadlineNanos() {
        return readyDeadlineNanos;
    }

    /** For testing: when origin fallback ends for the current outage. */
    long fallbackDeadlineNanos() {
        return fallbackDeadlineNanos;
    }

    /** For testing: the override map this session sends in {@code aws.config}. */
    Map<String, Object> overrides() {
        return overrides;
    }

    /**
     * Return the latest successfully resolved Cache configuration.
     *
     * @return the latest config, or {@code null} before the session opens
     */
    public ResolvedConfig getConfig() {
        return snapshot != null ? snapshot.config : null;
    }

    /**
     * For testing: force the "overrides not yet acknowledged" state that production code cannot
     * currently reach after a successful open (there is no code path that changes the overrides after
     * construction).
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

    /** For testing: the config refresher started by {@link #open()}, or null before it starts. */
    Refresher configRefresher() {
        return configRefresher;
    }

    /** For testing: the AUTH refresher started by {@link #open()}, or null before it starts. */
    Refresher reauthRefresher() {
        return reauthRefresher;
    }

    /**
     * Open the session: connect, negotiate RESP3 and authenticate, perform the config exchange,
     * compile the resolved predicates, then start config and authentication refresh.
     *
     * <p>Connect/handshake/config failures are retried with a new connector and fresh credentials
     * until the policy's connection timeout has passed. Each attempt is capped by the time left, and
     * no backoff sleep runs past the deadline. When the time runs out, a connection failure is thrown
     * as a timeout with the last failure as its cause, and any other failure is thrown unchanged.
     * Once a connector is live, refresher construction and startup are local session setup rather
     * than connectivity, so a failure there closes the connector and fails immediately instead of
     * repeating a successful handshake. The session becomes {@code OPEN} only once both refreshers
     * have started.
     *
     * <p>Lifecycle transitions are synchronized against {@link #close()}, but blocking connection and
     * credential work runs outside the state lock. A concurrent close can therefore tear down an
     * in-flight open; the opener checks that it still owns the lifecycle before publishing the config
     * or starting the refreshers.
     */
    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    public void open() {
        synchronized (stateLock) {
            if (state != State.CREATED) {
                throw new ElastiCacheIntegrationsError(
                        "open() called on a " + state.name().toLowerCase(Locale.ROOT) + " session");
            }
            if (opening) {
                throw new ElastiCacheIntegrationsError("open() called while session is opening");
            }
            opening = true;
        }

        try {
            RuntimeException lastFailure = null;
            ElastiCacheIntegrationsConnector established = null;
            ConnectionSnapshot establishedSnapshot = null;
            long timeoutMs = reconnectPolicy.getConnectionTimeoutMs();
            long deadline = saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMs));
            boolean timedOut = false;
            for (long attempt = 1; ; attempt++) {
                long remainingNanos = nanosUntil(deadline);
                if (remainingNanos <= 0) {
                    timedOut = true;
                    break;
                }
                ElastiCacheIntegrationsConnector next = null;
                try {
                    next = connectorFactory.get();
                    synchronized (stateLock) {
                        if (state != State.CREATED) {
                            next.close();
                            throw new ElastiCacheIntegrationsConnectionError("Session closed while opening");
                        }
                        candidate = next;
                    }
                    ConnectionSnapshot snapshot =
                            initializeWithTimeout(next, nanosToMillisCeil(remainingNanos));
                    synchronized (stateLock) {
                        if (state != State.CREATED || !next.isReady()) {
                            next.close();
                            throw new ElastiCacheIntegrationsConnectionError(
                                    state == State.CREATED
                                            ? "Cache connection closed while opening"
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
                    if (!canRetry || state != State.CREATED) {
                        break;
                    }
                    long failedAttempt = attempt;
                    LOGGER.fine(
                            () ->
                                    "elasticache_integrations connect attempt "
                                            + failedAttempt
                                            + " failed ("
                                            + failure
                                            + "); retrying");
                    sleep(
                            Math.min(reconnectPolicy.delayFor(attempt), nanosToMillisCeil(nanosUntil(deadline))));
                }
            }

            if (established == null || establishedSnapshot == null) {
                synchronized (stateLock) {
                    state = State.CLOSED;
                }
                reconnectExecutor.shutdownNow();
                if (lastFailure == null) {
                    throw new ElastiCacheIntegrationsConnectionError("Cache initial connection failed");
                }
                // Auth errors are connection errors in Java but not in Python, so check them first.
                if (timedOut
                        && lastFailure instanceof ElastiCacheIntegrationsConnectionError
                        && !(lastFailure instanceof ElastiCacheIntegrationsAuthError)) {
                    throw new ElastiCacheIntegrationsConnectionError(
                            "opening a connection timed out after " + timeoutMs + "ms", lastFailure);
                }
                throw lastFailure;
            }

            Refresher nextConfigRefresher = null;
            Refresher nextReauthRefresher = null;
            try {
                nextConfigRefresher =
                        createRefresher(
                                establishedSnapshot.config.getRefreshInterval(),
                                this::refreshOnce,
                                "aws-elasticache-integration-client-refresher",
                                null);
                nextReauthRefresher =
                        createRefresher(
                                reconnectPolicy.getAuthRefreshIntervalMs() / 1000.0,
                                this::reauthenticateOnce,
                                "aws-elasticache-integration-client-reauth",
                                failures -> reconnectPolicy.delayFor(failures) / 1000.0);
                synchronized (stateLock) {
                    if (state != State.CREATED || !established.isReady()) {
                        throw new ElastiCacheIntegrationsConnectionError(
                                state == State.CREATED
                                        ? "Cache connection closed while opening"
                                        : "Session closed while opening");
                    }
                    nextConfigRefresher.start();
                    nextReauthRefresher.start();
                    candidate = null;
                    publish(established, establishedSnapshot);
                    configRefresher = nextConfigRefresher;
                    reauthRefresher = nextReauthRefresher;
                    state = State.OPEN;
                }
            } catch (RuntimeException failure) {
                synchronized (stateLock) {
                    if (candidate == established) {
                        candidate = null;
                    }
                }
                if (nextReauthRefresher != null) {
                    nextReauthRefresher.stop();
                }
                if (nextConfigRefresher != null) {
                    nextConfigRefresher.stop();
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
     * Construct a refresher for this session. Package-private for testing: tests override this to
     * observe background lifecycle without a public constructor or getter.
     */
    Refresher createRefresher(
            double intervalSeconds,
            Runnable refreshOnce,
            String name,
            Refresher.FailureDelay failureDelay) {
        return new Refresher(
                intervalSeconds, refreshOnce, name, reconnectPolicy.getJitterFactor(), failureDelay);
    }

    /**
     * The refresher's periodic callback: send one {@code aws.config} refresh and apply the result.
     *
     * <p>Sends the overrides if they have not yet been acknowledged by the server, otherwise sends
     * the no-argument form ("my overrides are unchanged"). A RESP3 null reply installs nothing; any
     * other reply is parsed, its predicates compiled, and installed as a single immutable snapshot,
     * and the refresher is rescheduled on the interval the new config carries. Either way the server
     * confirmed the config, restarting the staleness window. Nothing is sent while no connector is
     * available.
     *
     * <p>A {@link ElastiCacheIntegrationsError} is caught here so it cannot escape the refresher
     * thread and silently stop every future refresh. A failure that kills the connector starts
     * recovery immediately. Any other failure keeps the current config until the staleness window
     * passes, then drops the connector so a new one re-resolves it. A successful refresh never drops.
     * Replies from a connector that has since been closed or replaced are discarded.
     */
    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    void refreshOnce() {
        ElastiCacheIntegrationsConnector active;
        Refresher activeRefresher;
        Map<String, Object> payload;
        synchronized (stateLock) {
            if (state != State.OPEN || connector == null) {
                return;
            }
            active = connector;
            activeRefresher = configRefresher;
            payload = overridesSent ? null : overrides;
        }

        SerializedCommand command = ElastiCacheIntegrationsProtocol.serializeConfig(payload);
        RespValue reply;
        try {
            reply = active.execute(command.getCommandName(), command.getArgs().toArray(new byte[0][]));
        } catch (ElastiCacheIntegrationsError e) {
            refreshFailed(active, activeRefresher, e);
            return;
        }

        if (reply instanceof RespNull) {
            synchronized (stateLock) {
                if (state == State.OPEN && connector == active && configRefresher == activeRefresher) {
                    configConfirmed();
                }
            }
            return;
        }

        ResolvedConfig newConfig;
        PredicateEvaluator newPredicates;
        try {
            newConfig = ElastiCacheIntegrationsProtocol.parseConfigResponse(reply);
            newPredicates = PredicateEvaluator.compile(newConfig.getRaw());
        } catch (ElastiCacheIntegrationsError e) {
            refreshFailed(active, activeRefresher, e);
            return;
        }

        synchronized (stateLock) {
            if (state != State.OPEN || connector != active || configRefresher != activeRefresher) {
                // The session closed or reconnected while this refresh was in flight.
                return;
            }
            activeRefresher.setInterval(newConfig.getRefreshInterval());
            overridesSent = true;
            snapshot = new ConnectionSnapshot(newConfig, newPredicates);
            configConfirmed();
        }
    }

    /** Restart the staleness window after any confirmation from the server. */
    private void configConfirmed() {
        configDeadlineNanos = nanoClock.getAsLong() + stalenessDurationNanos;
    }

    /**
     * Whether the config has gone unconfirmed long enough to stop trusting it.
     *
     * @return true when no server confirmation arrived within the staleness window
     */
    public boolean isConfigStale() {
        synchronized (stateLock) {
            return nanoClock.getAsLong() - configDeadlineNanos >= 0;
        }
    }

    /**
     * Handle a failed refresh. A failure that killed the connector starts recovery. Otherwise log it,
     * and drop {@code active} once the staleness window has passed.
     *
     * <p>Does nothing if the connector was replaced while the refresh was in flight.
     */
    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    private void refreshFailed(
            ElastiCacheIntegrationsConnector active,
            Refresher activeRefresher,
            ElastiCacheIntegrationsError e) {
        if (active.isDead()) {
            beginReconnect(active);
            return;
        }
        boolean stale;
        synchronized (stateLock) {
            if (state != State.OPEN || connector != active || configRefresher != activeRefresher) {
                return;
            }
            stale = isConfigStale();
        }
        if (isPermanentConfigFailure(e)) {
            LOGGER.log(
                    Level.SEVERE,
                    "Config refresh failed: "
                            + configFailureCause(e)
                            + ". Keeping the last good config until the staleness window drops the"
                            + " connection",
                    e);
        } else {
            LOGGER.log(Level.WARNING, "Config refresh failed; keeping the current config", e);
        }
        if (stale) {
            LOGGER.warning(
                    "No config confirmed for "
                            + TimeUnit.NANOSECONDS.toSeconds(stalenessDurationNanos)
                            + "s; dropping the connection so a new one resolves it");
            beginReconnect(active);
        }
    }

    /** A config failure that cannot fix itself, so it is logged at SEVERE on every occurrence. */
    private static boolean isPermanentConfigFailure(ElastiCacheIntegrationsError e) {
        return e instanceof ElastiCacheIntegrationsOverridesRejected
                || e instanceof ElastiCacheIntegrationsConfigUnevaluable;
    }

    /** The cause of a permanent config failure, worded the same on every path. */
    private static String configFailureCause(ElastiCacheIntegrationsError e) {
        return e instanceof ElastiCacheIntegrationsOverridesRejected
                ? "the proxy rejected the overrides (CONFIG_INVALID)"
                : "the config has a predicate this client cannot evaluate";
    }

    /**
     * Obtain fresh credentials and run plain AUTH on the live connector.
     *
     * <p>The connection has already negotiated RESP and started its reader, so this uses the normal
     * FIFO-correlated command path. Failures escape to the refresher, which retries with reconnect
     * backoff. A reconnect in progress has no live connector and is skipped.
     */
    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    void reauthenticateOnce() {
        ElastiCacheIntegrationsConnector active;
        synchronized (stateLock) {
            if (state != State.OPEN || connector == null) {
                return;
            }
            active = connector;
        }

        AuthCredentials credentials;
        try {
            credentials = authProvider.get();
        } catch (RuntimeException failure) {
            throw new ElastiCacheIntegrationsConnectionError("Auth provider failed", failure);
        }
        if (credentials == null) {
            throw new ElastiCacheIntegrationsConnectionError("Auth provider returned null credentials");
        }

        synchronized (stateLock) {
            if (state != State.OPEN || connector != active) {
                return;
            }
        }
        try {
            active.reauthenticate(credentials.getUserId(), credentials.getToken());
        } catch (ElastiCacheIntegrationsAuthError authFailure) {
            if (!active.isDead()) {
                forceRefreshAfterRejection(authFailure);
            }
            throw authFailure;
        }
    }

    /**
     * Route a request to Cache, or return {@code null} to let the caller handle it.
     *
     * <p>A request that finds no ready connector waits until the current outage's ready deadline for
     * a reconnect, and is served on the new connector if one arrives. After the wait, replay-safe
     * requests fall back to origin while the fallback window is open. Routed writes fail without
     * contacting origin.
     *
     * @param request the request to route
     * @return a Cache response, or {@code null} for signed origin fallback
     * @throws ElastiCacheIntegrationsCacheUnavailable if the cache is disconnected, or replied {@code
     *     AUTH_ERROR}, and the fallback window has closed
     */
    public ElastiCacheIntegrationsResponse request(ElastiCacheIntegrationsRequest request) {
        return request(request, new ElastiCacheIntegrationsRequestCancellation());
    }

    ElastiCacheIntegrationsResponse request(
            ElastiCacheIntegrationsRequest request,
            ElastiCacheIntegrationsRequestCancellation cancellation) {
        ensureUsable();
        if (cancellation.isCancelled()) {
            throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
        }
        boolean replaySafe = Idempotency.isIdempotent(request.getMetadata());
        boolean retriedAfterAuthError = false;
        while (true) {
            ConnectionSnapshot currentSnapshot = snapshot;
            PredicateEvaluator evaluator =
                    currentSnapshot != null ? currentSnapshot.predicateEvaluator : null;
            if (evaluator != null && !evaluator.shouldRouteToCache(request)) {
                return null;
            }

            ElastiCacheIntegrationsConnector active = connector;
            if (active == null || !active.isReady()) {
                beginReconnect(active);
                if (awaitReconnectOrFallback(cancellation, replaySafe)) {
                    continue;
                }
                return null;
            }

            try {
                return active.request(request, cancellation);
            } catch (ElastiCacheIntegrationsRejectedError failure) {
                if (AUTH_ERROR.equals(failure.getCode())) {
                    beginReconnect(active);
                    if (reconnectPolicy.getFallbackDurationSeconds() == 0) {
                        throw new ElastiCacheIntegrationsCacheUnavailable(
                                "Cache rejected the request with AUTH_ERROR and origin fallback is disabled");
                    }
                    if (replaySafe) {
                        return null;
                    }
                    if (retriedAfterAuthError) {
                        throw new ElastiCacheIntegrationsCacheUnavailable(
                                "Routed write was rejected with AUTH_ERROR after reconnect");
                    }
                    retriedAfterAuthError = true;
                    awaitReconnectOrFallback(cancellation, false);
                    continue;
                }
                throw failure;
            } catch (ElastiCacheIntegrationsError failure) {
                if (active.isDead()) {
                    beginReconnect(active);
                }
                throw failure;
            }
        }
    }

    /**
     * Return whether a request may go to origin without first reaching Cache.
     *
     * <p>Package-private for async capacity-rejection paths.
     */
    boolean canFallbackWithoutCache(ElastiCacheIntegrationsRequest request) {
        ConnectionSnapshot currentSnapshot = snapshot;
        PredicateEvaluator evaluator =
                currentSnapshot != null ? currentSnapshot.predicateEvaluator : null;
        return (evaluator != null && !evaluator.shouldRouteToCache(request))
                || (Idempotency.isIdempotent(request.getMetadata())
                        && reconnectPolicy.getFallbackDurationSeconds() != 0);
    }

    /**
     * Stop both refreshers, then close the connector. Idempotent.
     *
     * <p>The refreshers are stopped strictly before the connector closes, so background work can
     * never fire into a half-closed connection during an orderly shutdown. The state transition is
     * synchronized against {@link #open()}, so an in-flight opener cannot publish or start a
     * refresher after shutdown wins the lifecycle.
     *
     * <p>Marking the session closed and capturing the refreshers happen under the lock; joining their
     * threads and closing the connector happen after the lock is released. Holding the lock across
     * the join would risk a stall if refresh publication ever needed it too.
     */
    @Override
    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    public void close() {
        ElastiCacheIntegrationsConnector active;
        ElastiCacheIntegrationsConnector openingConnector;
        Refresher activeConfigRefresher;
        Refresher activeReauthRefresher;
        synchronized (stateLock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            active = connector;
            openingConnector = candidate;
            connector = null;
            candidate = null;
            activeConfigRefresher = configRefresher;
            activeReauthRefresher = reauthRefresher;
            configRefresher = null;
            reauthRefresher = null;
            stateLock.notifyAll();
        }
        if (activeReauthRefresher != null) {
            activeReauthRefresher.stop();
        }
        if (activeConfigRefresher != null) {
            activeConfigRefresher.stop();
        }
        if (openingConnector != null && openingConnector != active) {
            openingConnector.close();
        }
        if (active != null) {
            active.close();
        }
        reconnectExecutor.shutdownNow();
    }

    /**
     * Return whether requests may use this session.
     *
     * @return true while the session is open or reconnecting
     */
    public boolean isOpen() {
        State current = state;
        return current == State.OPEN || current == State.RECONNECTING;
    }

    /** Return whether a configured connector is currently available for requests. */
    boolean isConnectionHealthy() {
        ElastiCacheIntegrationsConnector active = connector;
        return state == State.OPEN && active != null && active.isReady();
    }

    private ConnectionSnapshot initializeWithTimeout(
            ElastiCacheIntegrationsConnector next, long timeoutMs) {
        FutureTask<ConnectionSnapshot> task = new FutureTask<>(() -> initialize(next));
        Thread thread =
                new Thread(
                        task, "aws-elasticache-integration-client-connect-" + THREAD_IDS.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            next.close();
            task.cancel(true);
            throw new ElastiCacheIntegrationsConnectionError(
                    "opening a connection timed out after " + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            next.close();
            throw new ElastiCacheIntegrationsConnectionError(
                    "Interrupted while opening Cache connection", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new ElastiCacheIntegrationsConnectionError(
                    "Cache connection initialization failed", cause);
        }
    }

    private ConnectionSnapshot initialize(ElastiCacheIntegrationsConnector next) {
        next.setOnDisconnect(() -> beginReconnect(next));
        next.connect();
        AuthCredentials credentials;
        try {
            credentials = authProvider.get();
        } catch (RuntimeException failure) {
            throw new ElastiCacheIntegrationsConnectionError("Auth provider failed", failure);
        }
        if (credentials == null) {
            throw new ElastiCacheIntegrationsConnectionError("Auth provider returned null credentials");
        }
        try {
            next.handshake(credentials.getUserId(), credentials.getToken());
        } catch (ElastiCacheIntegrationsAuthError authFailure) {
            forceRefreshAfterRejection(authFailure);
            throw authFailure;
        }
        try {
            ResolvedConfig nextConfig = next.configureAndStartReader(overrides);
            return new ConnectionSnapshot(nextConfig, PredicateEvaluator.compile(nextConfig.getRaw()));
        } catch (ElastiCacheIntegrationsError e) {
            // Logged on every attempt: the reconnect loop discards its failures, and these never
            // fix themselves.
            if (isPermanentConfigFailure(e)) {
                LOGGER.log(
                        Level.SEVERE,
                        "Configuring a connection failed: "
                                + configFailureCause(e)
                                + ". The connection is closed and retried",
                        e);
            }
            throw e;
        }
    }

    private void forceRefreshAfterRejection(ElastiCacheIntegrationsAuthError authFailure) {
        if (!(authProvider instanceof ManagedIamAuthProvider)) {
            return;
        }
        try {
            ((ManagedIamAuthProvider) authProvider).refresh();
        } catch (RuntimeException refreshFailure) {
            authFailure.addSuppressed(refreshFailure);
        }
    }

    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    private void beginReconnect(ElastiCacheIntegrationsConnector failed) {
        synchronized (stateLock) {
            if (state == State.CLOSED || state == State.CREATED) {
                return;
            }
            if (state == State.OPEN) {
                ElastiCacheIntegrationsConnector current = connector;
                if (current != null && current.isReady() && current != failed) {
                    return;
                }
                startOutageDeadlines();
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

    @SuppressWarnings("ReferenceEquality") // Identity tells whether a connector is still current.
    private void reconnectForever() {
        long attempt = 0;
        try {
            while (state == State.RECONNECTING) {
                attempt++;
                ElastiCacheIntegrationsConnector next = null;
                try {
                    next = connectorFactory.get();
                    synchronized (stateLock) {
                        if (state == State.CLOSED) {
                            next.close();
                            return;
                        }
                        candidate = next;
                    }
                    ConnectionSnapshot snapshot =
                            initializeWithTimeout(next, ReconnectPolicy.RECONNECT_ATTEMPT_TIMEOUT_MS);
                    synchronized (stateLock) {
                        candidate = null;
                        if (state == State.CLOSED || !next.isReady()) {
                            next.close();
                            if (state == State.CLOSED) {
                                return;
                            }
                            throw new ElastiCacheIntegrationsConnectionError(
                                    "Cache reconnect candidate closed before publication");
                        }
                        publish(next, snapshot);
                        state = State.OPEN;
                        stateLock.notifyAll();
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

    private void publish(ElastiCacheIntegrationsConnector next, ConnectionSnapshot snapshot) {
        Refresher activeRefresher = configRefresher;
        if (activeRefresher != null) {
            activeRefresher.setInterval(snapshot.config.getRefreshInterval());
        }
        overridesSent = true;
        this.snapshot = snapshot;
        configConfirmed();
        connector = next;
    }

    /**
     * Set both deadlines of a new outage from one clock reading. The fallback window starts when the
     * reconnect wait ends. Callers hold {@code stateLock} and clear the connector afterwards, so a
     * request that sees no connector also sees the new deadlines.
     */
    private void startOutageDeadlines() {
        long ready =
                saturatedAdd(
                        nanoClock.getAsLong(),
                        TimeUnit.MILLISECONDS.toNanos(reconnectPolicy.getConnectionTimeoutMs()));
        long seconds = reconnectPolicy.getFallbackDurationSeconds();
        readyDeadlineNanos = ready;
        fallbackDeadlineNanos =
                seconds < 0 ? Long.MAX_VALUE : saturatedAdd(ready, TimeUnit.SECONDS.toNanos(seconds));
    }

    /** Add a non-negative duration, stopping at {@link Long#MAX_VALUE} instead of overflowing. */
    private static long saturatedAdd(long base, long nanos) {
        long sum = base + nanos;
        return sum < base ? Long.MAX_VALUE : sum;
    }

    /** Return the nanoseconds left before {@code deadline}, or 0 once it has passed. */
    private static long nanosUntil(long deadline) {
        long now = System.nanoTime();
        if (deadline <= now) {
            return 0;
        }
        long remaining = deadline - now;
        // The difference overflows when the deadline is Long.MAX_VALUE and the clock is negative.
        return remaining < 0 ? Long.MAX_VALUE : remaining;
    }

    private static long nanosToMillisCeil(long nanos) {
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos);
        return nanos > TimeUnit.MILLISECONDS.toNanos(millis) ? millis + 1 : Math.max(1, millis);
    }

    /**
     * Wait for this outage's shared reconnect deadline.
     *
     * @return true when Cache reconnected, or false when the request may fall back to origin
     */
    private boolean awaitReconnectOrFallback(
            ElastiCacheIntegrationsRequestCancellation cancellation, boolean fallbackSafe) {
        Runnable wakeOnCancel = this::wakeStateWaiters;
        cancellation.register(wakeOnCancel);
        try {
            synchronized (stateLock) {
                while (state == State.RECONNECTING) {
                    if (cancellation.isCancelled()) {
                        throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
                    }
                    long now = nanoClock.getAsLong();
                    long remainingNanos = readyDeadlineNanos - now;
                    if (remainingNanos <= 0) {
                        long fallbackDeadline = fallbackDeadlineNanos;
                        if (fallbackSafe && (fallbackDeadline == Long.MAX_VALUE || now < fallbackDeadline)) {
                            return false;
                        }
                        if (!fallbackSafe) {
                            throw new ElastiCacheIntegrationsCacheUnavailable(
                                    "Routed write was not sent because Cache did not reconnect before the"
                                            + " connection timeout");
                        }
                        throw new ElastiCacheIntegrationsCacheUnavailable(
                                "Cache has no cache connection and origin fallback is no longer allowed");
                    }
                    try {
                        stateLock.wait(nanosToMillisCeil(remainingNanos));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (cancellation.isCancelled()) {
                            throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
                        }
                        throw new ElastiCacheIntegrationsCacheUnavailable(
                                "Interrupted while waiting for Cache to reconnect");
                    }
                }
                ensureUsable();
                return true;
            }
        } finally {
            cancellation.clear(wakeOnCancel);
        }
    }

    /** Wake every thread waiting on the state lock so it rechecks its exit conditions. */
    void wakeStateWaiters() {
        synchronized (stateLock) {
            stateLock.notifyAll();
        }
    }

    /** Wait out a reconnect backoff. Package-private for tests. */
    boolean waitForRetry(long delayMs) {
        synchronized (stateLock) {
            if (state != State.RECONNECTING) {
                return false;
            }
            // Loop so a spurious wakeup cannot cut the backoff short.
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
            long remainingNanos = deadline - System.nanoTime();
            while (state == State.RECONNECTING && remainingNanos > 0) {
                // Round up so a sub-millisecond remainder still waits instead of ending early.
                long waitMs = (remainingNanos + 999_999) / 1_000_000;
                try {
                    stateLock.wait(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return state == State.RECONNECTING;
        }
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ElastiCacheIntegrationsConnectionError(
                    "Interrupted during Cache connection backoff", e);
        }
    }

    private void ensureUsable() {
        State current = state;
        if (current != State.OPEN && current != State.RECONNECTING) {
            throw new ElastiCacheIntegrationsError(
                    "request() called on a " + current.name().toLowerCase(Locale.ROOT) + " session");
        }
    }

    private static Supplier<ElastiCacheIntegrationsConnector> singleConnectorFactory(
            ElastiCacheIntegrationsConnector connector) {
        if (connector == null) {
            throw new NullPointerException("connector");
        }
        return new Supplier<ElastiCacheIntegrationsConnector>() {
            private boolean supplied;

            @Override
            public synchronized ElastiCacheIntegrationsConnector get() {
                if (supplied) {
                    throw new ElastiCacheIntegrationsConnectionError(
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

        private ConnectionSnapshot(ResolvedConfig config, PredicateEvaluator predicateEvaluator) {
            this.config = config;
            this.predicateEvaluator = predicateEvaluator;
        }
    }
}
