package software.amazon.elasticache.integrations.connection;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import software.amazon.elasticache.integrations.auth.AuthCredentials;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsAuthError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.protocol.SerializedCommand;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespCodec;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespInteger;
import software.amazon.elasticache.integrations.resp.RespMap;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/**
 * A thread-safe connection to the Cache cache with concurrent request support.
 *
 * <p>Handles TLS setup, the {@code HELLO 3 AUTH} handshake, the {@code aws.config} exchange, and
 * {@code aws.request} command execution. Multiple threads can safely call {@link
 * #request(ElastiCacheIntegrationsRequest)} concurrently — responses are correlated via FIFO
 * position or deferred request ID.
 *
 * <p>Lifecycle: {@code connect()} → {@code handshake(String, String)} → {@code
 * configureAndStartReader()} → {@code request()} (repeated) → {@code close()}.
 *
 * <p>For simpler use, {@link #connectAndAuthenticate(String, String)} and {@link
 * #connectAndAuthenticate(Supplier, Map)} combine these steps.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>This connection is thread-safe once fully connected. The {@code request()} method uses a
 * send-lock to serialize writes while allowing concurrent callers. A background reader thread
 * resolves positional and request-ID responses.
 *
 * <h2>Timeout Behavior</h2>
 *
 * <p>A timeout while a request is still FIFO-correlated closes the connection, because a late reply
 * could be matched to the wrong caller. A deferred request tracked by ID can time out
 * independently; a later push for that ID is dropped.
 */
public class ElastiCacheIntegrationsConnector implements Closeable {

    /**
     * Default connection timeout in milliseconds: the budget for each step of bringing a connection
     * up.
     */
    public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 5000;

    /** Default request timeout in milliseconds. */
    public static final int DEFAULT_REQUEST_TIMEOUT_MS = 5000;

    /** Default maximum pending requests. */
    public static final int DEFAULT_MAX_PENDING = 100;

    private static final String[] PREFERRED_TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final int RESP3_PROTOCOL_VERSION = 3;
    private static final RespBulkString HELLO_PROTO_KEY =
            new RespBulkString("proto".getBytes(StandardCharsets.UTF_8));
    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread =
                                    new Thread(runnable, "aws-elasticache-integration-client-timeout-watchdog");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final int connectionTimeoutMs;
    private final int requestTimeoutMs;
    private final int maxPending;
    private final int socketReadSize;

    // Connection state - guarded by stateLock
    private final Object stateLock = new Object();
    private SSLSocket socket;
    private OutputStream outputStream;
    private ResolvedConfig config;
    private PendingRequests pending;
    private SocketReader reader;
    private Thread readerThread;
    private volatile boolean dead = false;
    private volatile boolean closed = false;
    private volatile Runnable onDisconnect;
    private final AtomicBoolean disconnectNotified = new AtomicBoolean();

    // Send lock - serializes register + write to maintain FIFO correlation
    private final Lock sendLock = new ReentrantLock();

    /**
     * Create a connection to the specified host and port using the given SSL context.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context to use for TLS (allows custom trust stores for testing)
     */
    public ElastiCacheIntegrationsConnector(String host, int port, SSLContext sslContext) {
        this(
                host,
                port,
                sslContext,
                DEFAULT_CONNECTION_TIMEOUT_MS,
                DEFAULT_REQUEST_TIMEOUT_MS,
                DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectionTimeoutMs maximum time, in milliseconds, for each step of bringing the
     *     connection up: the TCP connect and TLS handshake together, {@code HELLO 3 AUTH}, and the
     *     first {@code aws.config}
     * @param requestTimeoutMs maximum time, in milliseconds, to wait for each command on a live
     *     connection
     */
    public ElastiCacheIntegrationsConnector(
            String host, int port, SSLContext sslContext, int connectionTimeoutMs, int requestTimeoutMs) {
        this(host, port, sslContext, connectionTimeoutMs, requestTimeoutMs, DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts and pending queue size.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectionTimeoutMs maximum time, in milliseconds, for each step of bringing the
     *     connection up
     * @param requestTimeoutMs maximum time, in milliseconds, to wait for each command on a live
     *     connection
     * @param maxPending maximum requests awaiting either a FIFO or a deferred reply
     */
    public ElastiCacheIntegrationsConnector(
            String host,
            int port,
            SSLContext sslContext,
            int connectionTimeoutMs,
            int requestTimeoutMs,
            int maxPending) {
        this(
                host,
                port,
                sslContext,
                connectionTimeoutMs,
                requestTimeoutMs,
                maxPending,
                SocketReader.DEFAULT_READ_SIZE);
    }

    /**
     * Create a connection with custom timeouts, pending queue size, and read size.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectionTimeoutMs maximum time, in milliseconds, for each step of bringing the
     *     connection up
     * @param requestTimeoutMs maximum time, in milliseconds, to wait for each command on a live
     *     connection
     * @param maxPending maximum requests awaiting either a FIFO or a deferred reply
     * @param socketReadSize most bytes to request from the socket per read
     */
    public ElastiCacheIntegrationsConnector(
            String host,
            int port,
            SSLContext sslContext,
            int connectionTimeoutMs,
            int requestTimeoutMs,
            int maxPending,
            int socketReadSize) {
        requirePositive("connectionTimeoutMs", connectionTimeoutMs);
        requirePositive("requestTimeoutMs", requestTimeoutMs);
        requirePositive("maxPending", maxPending);
        requirePositive("socketReadSize", socketReadSize);
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.connectionTimeoutMs = connectionTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxPending = maxPending;
        this.socketReadSize = socketReadSize;
    }

    // =========================================================================
    // Lifecycle: connect → handshake → configure → request → close
    // =========================================================================

    /**
     * Establish the TCP + TLS connection.
     *
     * <p>After this returns, the socket is connected and TLS handshake is complete, but no Cache
     * protocol handshake has occurred. Call {@link #handshake(String, String)} next. The TCP connect
     * and the TLS handshake share one {@code connectionTimeoutMs} deadline: the handshake gets what
     * the TCP connect left, and does not start when nothing is left.
     *
     * @throws ElastiCacheIntegrationsConnectionError if the connection fails or the deadline passes
     */
    public void connect() {
        Socket plainSocket = null;
        synchronized (stateLock) {
            if (socket != null) {
                throw new IllegalStateException("Already connected");
            }
            if (closed) {
                throw new IllegalStateException("Connection was closed");
            }
            if (dead) {
                throw new IllegalStateException("Connection is dead and cannot be reconnected");
            }

            long connectDeadlineNanos = deadlineAfter(connectionTimeoutMs);
            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                plainSocket = new Socket();
                plainSocket.setTcpNoDelay(true); // Disable Nagle's algorithm
                plainSocket.setKeepAlive(true);
                plainSocket.connect(new InetSocketAddress(host, port), connectionTimeoutMs);

                // createSocket takes ownership of plainSocket on success (autoClose=true).
                // Keep plainSocket reference until then for cleanup on failure.
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
                plainSocket = null; // Transfer ownership succeeded

                socket = sslSocket;

                // Enable hostname verification - validates that the server's certificate
                // matches the hostname we're connecting to. SSLContext.getDefault() validates
                // the certificate chain, but doesn't verify the hostname by default.
                javax.net.ssl.SSLParameters sslParams = socket.getSSLParameters();
                sslParams.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(sslParams);

                // Filter to TLS 1.2+ (don't hardcode protocol names that may not be supported)
                String[] enabledProtocols = filterSupportedProtocols(socket.getSupportedProtocols());
                if (enabledProtocols.length == 0) {
                    throw new IOException("No supported TLS protocols (need TLS 1.2 or higher)");
                }
                socket.setEnabledProtocols(enabledProtocols);
                long handshakeTimeoutMs =
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos(connectDeadlineNanos));
                if (handshakeTimeoutMs <= 0) {
                    throw new ElastiCacheIntegrationsTimeoutError(
                            "TCP connect used the whole " + connectionTimeoutMs + "ms connection timeout");
                }
                socket.setSoTimeout((int) handshakeTimeoutMs);
                runWithDeadline(socket::startHandshake, (int) handshakeTimeoutMs, "TLS handshake");
                // HELLO 3 AUTH and the first aws.config each get a full connection timeout; the
                // session bounds the whole attempt with the same value.
                socket.setSoTimeout(connectionTimeoutMs);

                outputStream = socket.getOutputStream();

                // Initialize pending queue and reader (not started yet)
                pending = new PendingRequests(maxPending);
                reader =
                        new SocketReader(
                                socket.getInputStream(),
                                pending,
                                this::markDead,
                                SocketReader.DEFAULT_MAX_RESPONSE_SIZE,
                                socketReadSize);
            } catch (ElastiCacheIntegrationsTimeoutError | SocketTimeoutException e) {
                closeSocketQuietly();
                throw new ElastiCacheIntegrationsConnectionError(
                        "Timed out connecting to " + host + ":" + port, e);
            } catch (SSLException e) {
                // Ahead of IOException, which SSLException extends: a rejected certificate must not
                // report as an unreachable cache.
                closeSocketQuietly();
                throw new ElastiCacheIntegrationsConnectionError(
                        "TLS handshake failed to " + host + ":" + port, e);
            } catch (IOException e) {
                closeSocketQuietly();
                throw new ElastiCacheIntegrationsConnectionError(
                        "Failed to connect to " + host + ":" + port, e);
            } catch (RuntimeException e) {
                // Catches IllegalArgumentException from setEnabledProtocols if protocols unsupported
                closeSocketQuietly();
                throw new ElastiCacheIntegrationsConnectionError(
                        "Failed to configure TLS for " + host + ":" + port, e);
            } finally {
                // Clean up plainSocket if createSocket failed before taking ownership
                if (plainSocket != null) {
                    try {
                        plainSocket.close();
                    } catch (IOException e) {
                        // Best-effort cleanup
                    }
                }
            }
        }

        // Check for close-during-connect race
        synchronized (stateLock) {
            if (closed) {
                dead = true;
                closeSocketQuietly();
                throw new ElastiCacheIntegrationsConnectionError("Connection closed during connect");
            }
        }
    }

    /** Filter TLS protocols to only enable TLS 1.2 and higher. */
    private static String[] filterSupportedProtocols(String[] supportedProtocols) {
        List<String> supported = Arrays.asList(supportedProtocols);
        List<String> enabled = new ArrayList<>();
        for (String preferred : PREFERRED_TLS_PROTOCOLS) {
            if (supported.contains(preferred)) {
                enabled.add(preferred);
            }
        }
        return enabled.toArray(new String[0]);
    }

    /**
     * Negotiate RESP3 and authenticate with {@code HELLO 3 AUTH <user> <token>}.
     *
     * <p>Must be called after {@link #connect()} and before any requests. The reader thread is not
     * started yet, so this uses synchronous reads.
     *
     * @param userId the user ID for inline AUTH (e.g., "default.iam-user")
     * @param authToken the inline AUTH token/password
     * @throws ElastiCacheIntegrationsConnectionError if HELLO is rejected, lost, times out, or does
     *     not confirm RESP3
     */
    public void handshake(String userId, String authToken) {
        ensureConnectedNotDead();
        if (readerThread != null) {
            throw new IllegalStateException(
                    "Reader already started - handshake must be called before configureAndStartReader");
        }

        try {
            byte[] helloCmd =
                    RespCodec.encodeCommand(
                            "HELLO",
                            "3".getBytes(StandardCharsets.UTF_8),
                            "AUTH".getBytes(StandardCharsets.UTF_8),
                            userId.getBytes(StandardCharsets.UTF_8),
                            authToken.getBytes(StandardCharsets.UTF_8));
            sendRawDirect(helloCmd);
            verifyHelloReply(reader.readFrame());
        } catch (ElastiCacheIntegrationsTimeoutError e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("HELLO 3 timed out - connection closed", e);
        } catch (ElastiCacheIntegrationsConnectionLost e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("HELLO 3 failed: connection lost", e);
        } catch (ElastiCacheIntegrationsConnectionError e) {
            throw e;
        } catch (Exception e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("HELLO 3 handshake failed", e);
        }
    }

    private void verifyHelloReply(RespValue reply) {
        if (reply instanceof RespError) {
            markDead();
            throw new ElastiCacheIntegrationsAuthError(
                    "HELLO 3 rejected: " + ((RespError) reply).getCode());
        }
        if (!(reply instanceof RespMap)) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError(
                    "HELLO 3 failed: expected a map reply, got " + reply.getClass().getSimpleName());
        }
        RespValue proto = ((RespMap) reply).getEntries().get(HELLO_PROTO_KEY);
        if (!(proto instanceof RespInteger)
                || ((RespInteger) proto).getValue() != RESP3_PROTOCOL_VERSION) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError(
                    "HELLO 3 failed: reply did not report proto " + RESP3_PROTOCOL_VERSION);
        }
    }

    /**
     * Re-authenticate an already-ready connection with a fresh token.
     *
     * <p>An explicit RESP error rejects the new token but leaves the connection open so the proxy can
     * continue serving with the last valid authentication state. A timeout or any unexpected
     * non-error reply invalidates the FIFO stream and closes the connection.
     *
     * @param userId the user ID for AUTH
     * @param authToken the fresh AUTH token/password
     * @throws ElastiCacheIntegrationsAuthError if AUTH is rejected or returns an unexpected reply
     * @throws ElastiCacheIntegrationsConnectionError if AUTH times out
     */
    public void reauthenticate(String userId, String authToken) {
        ensureReady();
        byte[] encoded =
                RespCodec.encodeCommand(
                        "AUTH",
                        userId.getBytes(StandardCharsets.UTF_8),
                        authToken.getBytes(StandardCharsets.UTF_8));
        RespValue reply;
        try {
            reply =
                    sendAndAwait(
                            encoded, new ElastiCacheIntegrationsRequestCancellation(), false, requestTimeoutMs);
        } catch (ElastiCacheIntegrationsTimeoutError e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("IAM AUTH timed out - connection closed", e);
        }
        verifyReauthenticationReply(reply);
    }

    private void verifyReauthenticationReply(RespValue reply) {
        if (reply instanceof RespError) {
            throw new ElastiCacheIntegrationsAuthError(
                    "IAM AUTH rejected: " + ((RespError) reply).getCode());
        }
        if (reply instanceof RespSimpleString
                && "OK".equals(new String(((RespSimpleString) reply).getValue(), StandardCharsets.UTF_8))) {
            return;
        }
        markDead();
        throw new ElastiCacheIntegrationsAuthError(
                "IAM AUTH failed: unexpected reply of type " + reply.getClass().getSimpleName());
    }

    /**
     * Perform the aws.config handshake and start the background reader thread.
     *
     * <p>After this returns, the connection is fully ready for concurrent requests.
     *
     * @param overrides optional configuration overrides (may be null or empty)
     * @return the resolved configuration from the cache
     * @throws ElastiCacheIntegrationsConnectionError if config handshake fails
     * @throws ElastiCacheIntegrationsOverridesRejected if the cache rejects the overrides
     *     (CONFIG_INVALID)
     */
    public ResolvedConfig configureAndStartReader(Map<String, Object> overrides) {
        ensureConnectedNotDead();
        if (readerThread != null) {
            throw new IllegalStateException("Reader already started");
        }

        // Send config (still synchronous - reader not running yet)
        try {
            // The zero-argument form means "unchanged since the last send" and is only valid
            // after a connection has already acknowledged explicit overrides. Bring-up always
            // sends an object, including {} when the caller supplies null.
            Map<String, Object> initialOverrides = overrides != null ? overrides : Collections.emptyMap();
            SerializedCommand configCmd =
                    ElastiCacheIntegrationsProtocol.serializeConfig(initialOverrides);
            byte[] encoded =
                    RespCodec.encodeCommand(
                            configCmd.getCommandName(), configCmd.getArgs().toArray(new byte[0][]));
            sendRawDirect(encoded);
            RespValue configReply = reader.readFrame();
            this.config = ElastiCacheIntegrationsProtocol.parseConfigResponse(configReply);
        } catch (ElastiCacheIntegrationsConnectionError | ElastiCacheIntegrationsOverridesRejected e) {
            // A rejected override is the caller's to fix, so it keeps its own type.
            markDead();
            throw e;
        } catch (Exception e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("Config handshake failed", e);
        }

        // Request timeouts are enforced per future below. A socket timeout here
        // would kill an otherwise healthy connection simply because it was idle.
        try {
            socket.setSoTimeout(0);
        } catch (IOException e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("Failed to configure background reader", e);
        }

        // Start the reader thread
        startReaderThread();

        return this.config;
    }

    /**
     * Convenience: connect, negotiate RESP3 with inline authentication, configure, and start reader.
     *
     * @param userId the user ID for inline AUTH (e.g., "default.iam-user")
     * @param authToken the inline AUTH token/password
     * @param overrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws ElastiCacheIntegrationsConnectionError if any step fails
     * @throws ElastiCacheIntegrationsOverridesRejected if the cache rejects the overrides
     *     (CONFIG_INVALID)
     */
    public ResolvedConfig connectAndAuthenticate(
            String userId, String authToken, Map<String, Object> overrides) {
        connect();
        handshake(userId, authToken);
        return configureAndStartReader(overrides);
    }

    /**
     * Convenience: connect, handshake with credentials provider, configure, and start reader.
     *
     * <p>The provider is called after TLS connect to get fresh credentials.
     *
     * @param authProvider supplies the inline AUTH credentials (called after TLS connect)
     * @param overrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws ElastiCacheIntegrationsConnectionError if any step fails
     * @throws ElastiCacheIntegrationsOverridesRejected if the cache rejects the overrides
     *     (CONFIG_INVALID)
     */
    public ResolvedConfig connectAndAuthenticate(
            Supplier<AuthCredentials> authProvider, Map<String, Object> overrides) {
        connect();
        AuthCredentials creds;
        try {
            creds = authProvider.get();
        } catch (Exception e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionError("Auth provider failed", e);
        }
        handshake(creds.getUserId(), creds.getToken());
        return configureAndStartReader(overrides);
    }

    /**
     * Convenience: connect and handshake with empty config overrides.
     *
     * @param userId the user ID for inline AUTH
     * @param authToken the password or IAM token for inline AUTH
     * @return the resolved configuration from the cache
     * @throws ElastiCacheIntegrationsConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(String userId, String authToken) {
        return connectAndAuthenticate(userId, authToken, Collections.emptyMap());
    }

    /**
     * Convenience: connect and handshake with provider and empty config overrides.
     *
     * @param authProvider supplies the inline AUTH credentials (called after TLS connect)
     * @return the resolved configuration from the cache
     * @throws ElastiCacheIntegrationsConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(Supplier<AuthCredentials> authProvider) {
        return connectAndAuthenticate(authProvider, Collections.emptyMap());
    }

    // =========================================================================
    // Request execution
    // =========================================================================

    /**
     * Send a {@code aws.request} and return the parsed response.
     *
     * <p>This method is thread-safe and can be called concurrently from multiple threads. Responses
     * are correlated by FIFO position or deferred request ID.
     *
     * @param request the request to send
     * @return the cache response
     * @throws ElastiCacheIntegrationsConnectionLost if the connection dies
     * @throws ElastiCacheIntegrationsTimeoutError if the request times out
     */
    public ElastiCacheIntegrationsResponse request(ElastiCacheIntegrationsRequest request) {
        return request(request, new ElastiCacheIntegrationsRequestCancellation());
    }

    /**
     * Send a {@code aws.request} with an external cancellation signal.
     *
     * @param request the request to send
     * @param cancellation cancellation signal for this request
     * @return the cache response
     */
    public ElastiCacheIntegrationsResponse request(
            ElastiCacheIntegrationsRequest request,
            ElastiCacheIntegrationsRequestCancellation cancellation) {
        ensureReady();

        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeRequest(request);
        byte[] encoded =
                RespCodec.encodeCommand(cmd.getCommandName(), cmd.getArgs().toArray(new byte[0][]));

        RespValue reply = sendAndAwait(encoded, cancellation, true, requestTimeoutMs);
        return ElastiCacheIntegrationsProtocol.parseRequestResponse(reply);
    }

    /**
     * Send a raw command and return the raw RESP response.
     *
     * <p>Useful for extending with additional commands. Thread-safe.
     *
     * @param command the command name
     * @param args command arguments
     * @return the RESP response
     */
    public RespValue execute(String command, byte[]... args) {
        ensureReady();
        byte[] encoded = RespCodec.encodeCommand(command, args);
        return sendAndAwait(
                encoded, new ElastiCacheIntegrationsRequestCancellation(), false, requestTimeoutMs);
    }

    // =========================================================================
    // State queries
    // =========================================================================

    /**
     * Get the resolved config from the last successful handshake.
     *
     * @return the resolved config, or {@code null} before the first successful handshake
     */
    public ResolvedConfig getConfig() {
        return config;
    }

    /**
     * Check if the connection is usable (connected and not dead).
     *
     * @return true when the socket is open and the connector is neither dead nor closed
     */
    public boolean isConnected() {
        synchronized (stateLock) {
            return socket != null && !socket.isClosed() && !dead && !closed;
        }
    }

    /**
     * Check if the connection is dead (failed and cannot be used).
     *
     * @return true after the connector has failed
     */
    public boolean isDead() {
        return dead;
    }

    /**
     * Check if the connection is fully ready for requests (reader thread running).
     *
     * @return true when the connector is connected and its reader thread has started
     */
    public boolean isReady() {
        synchronized (stateLock) {
            return isConnected() && readerThread != null;
        }
    }

    /**
     * Register a callback invoked once when this connector fails. An explicit {@link #close()} does
     * not invoke it.
     *
     * @param onDisconnect the callback, which runs at once if the connector is already dead
     */
    public void setOnDisconnect(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
        if (dead) {
            notifyDisconnect();
        }
    }

    /**
     * Release the connection, stop the reader thread, and fail pending requests. Idempotent.
     *
     * <p>A connector is single-use: once closed, it never connects again. Reconnecting creates a new
     * connector.
     */
    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            dead = true;

            // Fail all pending requests
            if (pending != null) {
                pending.failAll(new ElastiCacheIntegrationsConnectionLost("Connection closed"));
            }

            // Stop reader thread
            if (readerThread != null) {
                readerThread.interrupt();
            }

            closeSocketQuietly();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void startReaderThread() {
        readerThread =
                new Thread(
                        reader::runLoop, "aws-elasticache-integration-client-reader-" + host + ":" + port);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    /** Mark the connection as dead. Called by the reader thread on any error. Idempotent. */
    private void markDead() {
        boolean changed = false;
        synchronized (stateLock) {
            if (dead) {
                return;
            }
            dead = true;
            changed = true;
            closeSocketQuietly();
        }
        if (changed) {
            notifyDisconnect();
        }
    }

    private void notifyDisconnect() {
        Runnable callback = onDisconnect;
        if (callback != null && disconnectNotified.compareAndSet(false, true)) {
            callback.run();
        }
    }

    private void ensureConnectedNotDead() {
        if (dead) {
            throw new ElastiCacheIntegrationsConnectionLost("Connection is dead");
        }
        synchronized (stateLock) {
            if (socket == null) {
                throw new ElastiCacheIntegrationsConnectionLost("Connection is not established");
            }
            if (closed) {
                throw new ElastiCacheIntegrationsConnectionLost("Connection is closed");
            }
        }
    }

    /** Ensure connection is fully ready for requests (reader thread running). */
    private void ensureReady() {
        ensureConnectedNotDead();
        synchronized (stateLock) {
            if (readerThread == null) {
                throw new ElastiCacheIntegrationsConnectionLost(
                        "Connection handshake not complete - call configureAndStartReader() first");
            }
        }
    }

    /**
     * Send raw bytes directly, without FIFO correlation. Used during handshake before the reader
     * thread is started.
     */
    private void sendRawDirect(byte[] data) {
        try {
            long deadlineNanos = deadlineAfter(connectionTimeoutMs);
            writeWithDeadline(outputStream, data, deadlineNanos, "Initialization write");
        } catch (ElastiCacheIntegrationsTimeoutError e) {
            markDead();
            throw e;
        } catch (IOException e) {
            markDead();
            throw new ElastiCacheIntegrationsConnectionLost("Failed to write to socket", e);
        }
    }

    /**
     * Register, send, and await response using FIFO correlation. Uses the send lock to ensure atomic
     * register+send.
     *
     * @param timeoutMs the budget for this command
     */
    private RespValue sendAndAwait(
            byte[] encoded,
            ElastiCacheIntegrationsRequestCancellation cancellation,
            boolean deferredResponseAllowed,
            int timeoutMs) {
        if (cancellation.isCancelled()) {
            throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
        }

        CompletableFuture<RespValue> future;
        long deadlineNanos = deadlineAfter(timeoutMs);

        boolean locked;
        try {
            locked = sendLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ElastiCacheIntegrationsConnectionLost(
                    "Interrupted while waiting to send request", e);
        }
        if (!locked) {
            markDead();
            throw new ElastiCacheIntegrationsTimeoutError(
                    "Timed out acquiring send lock after " + timeoutMs + "ms");
        }
        try {
            if (cancellation.isCancelled()) {
                throw new ElastiCacheIntegrationsRequestCancellation.CancelledException();
            }

            // Must register before sending to maintain FIFO correlation
            future = pending.registerNewRequest(deferredResponseAllowed);

            // Snapshot outputStream under lock to avoid NPE race with concurrent close/markDead
            OutputStream out;
            synchronized (stateLock) {
                out = outputStream;
                if (out == null || dead) {
                    throw new ElastiCacheIntegrationsConnectionLost("Connection closed during send");
                }
            }

            try {
                writeWithDeadline(out, encoded, deadlineNanos, "Request write");
            } catch (ElastiCacheIntegrationsTimeoutError e) {
                markDead();
                pending.failAll(e);
                throw e;
            } catch (IOException e) {
                // Send failed - mark dead (future will be failed by markDead -> failAll)
                markDead();
                ElastiCacheIntegrationsConnectionLost failure =
                        new ElastiCacheIntegrationsConnectionLost("Failed to write to socket", e);
                pending.failAll(failure);
                throw failure;
            }
        } finally {
            sendLock.unlock();
        }

        Runnable cancellationAction =
                () ->
                        pending.cancel(
                                future, new ElastiCacheIntegrationsRequestCancellation.CancelledException());
        cancellation.register(cancellationAction);

        // Wait for response
        try {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                throw new TimeoutException();
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            ElastiCacheIntegrationsTimeoutError timeout =
                    new ElastiCacheIntegrationsTimeoutError(
                            "Cache request timed out after " + timeoutMs + "ms", e);
            PendingRequests.TimeoutDisposition disposition = pending.onTimeout(future, timeout);
            switch (disposition) {
                case ASYNC_REMOVED:
                    throw timeout;
                case COMPLETED:
                    try {
                        return future.get();
                    } catch (ExecutionException completedFailure) {
                        throw requestFailure(completedFailure.getCause());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        markDead();
                        ElastiCacheIntegrationsConnectionLost failure =
                                new ElastiCacheIntegrationsConnectionLost("Request interrupted", interrupted);
                        pending.failAll(failure);
                        throw failure;
                    }
                case FATAL:
                    markDead();
                    throw timeout;
                default:
                    throw new IllegalStateException("Unknown timeout disposition: " + disposition);
            }
        } catch (ExecutionException e) {
            // Reader thread failed the future - preserve the original exception type
            Throwable cause = e.getCause();
            if (cause instanceof ElastiCacheIntegrationsConnectionLost) {
                throw (ElastiCacheIntegrationsConnectionLost) cause;
            } else if (cause instanceof ElastiCacheIntegrationsTimeoutError) {
                throw (ElastiCacheIntegrationsTimeoutError) cause;
            } else if (cause instanceof ElastiCacheIntegrationsProtocolError) {
                throw (ElastiCacheIntegrationsProtocolError) cause;
            } else if (cause instanceof ElastiCacheIntegrationsError) {
                throw (ElastiCacheIntegrationsError) cause;
            } else {
                throw new ElastiCacheIntegrationsConnectionLost("Request failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markDead();
            pending.failAll(new ElastiCacheIntegrationsConnectionLost("Request interrupted", e));
            throw new ElastiCacheIntegrationsConnectionLost("Request interrupted", e);
        } finally {
            cancellation.clear(cancellationAction);
        }
    }

    private ElastiCacheIntegrationsError requestFailure(Throwable cause) {
        if (cause instanceof ElastiCacheIntegrationsError) {
            return (ElastiCacheIntegrationsError) cause;
        }
        return new ElastiCacheIntegrationsConnectionLost(
                "Cache request failed before a response was received", cause);
    }

    private static long deadlineAfter(int timeoutMs) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private void writeWithDeadline(
            OutputStream out, byte[] data, long deadlineNanos, String operation) throws IOException {
        long remainingNanos = remainingNanos(deadlineNanos);
        if (remainingNanos <= 0) {
            throw new ElastiCacheIntegrationsTimeoutError(operation + " timed out");
        }

        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        Socket socketToClose = socket;
        ScheduledFuture<?> timeout =
                TIMEOUT_EXECUTOR.schedule(
                        () -> {
                            if (completed.compareAndSet(false, true)) {
                                timedOut.set(true);
                                dead = true;
                                closeQuietly(socketToClose);
                            }
                        },
                        remainingNanos,
                        TimeUnit.NANOSECONDS);
        try {
            out.write(data);
            out.flush();
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new ElastiCacheIntegrationsTimeoutError(operation + " timed out", e);
            }
            throw e;
        } finally {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
            }
        }

        if (timedOut.get()) {
            throw new ElastiCacheIntegrationsTimeoutError(operation + " timed out");
        }
    }

    private void runWithDeadline(IoOperation operation, int timeoutMs, String description)
            throws IOException {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean timedOut = new AtomicBoolean();
        Socket socketToClose = socket;
        ScheduledFuture<?> timeout =
                TIMEOUT_EXECUTOR.schedule(
                        () -> {
                            if (completed.compareAndSet(false, true)) {
                                timedOut.set(true);
                                dead = true;
                                closeQuietly(socketToClose);
                            }
                        },
                        timeoutMs,
                        TimeUnit.MILLISECONDS);
        try {
            operation.run();
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new ElastiCacheIntegrationsTimeoutError(
                        description + " timed out after " + timeoutMs + "ms", e);
            }
            throw e;
        } finally {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
            }
        }
        if (timedOut.get()) {
            throw new ElastiCacheIntegrationsTimeoutError(
                    description + " timed out after " + timeoutMs + "ms");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort timeout cancellation.
            }
        }
    }

    @FunctionalInterface
    private interface IoOperation {
        void run() throws IOException;
    }

    private void closeSocketQuietly() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Best-effort close
            }
            socket = null;
            outputStream = null;
        }
    }
}
