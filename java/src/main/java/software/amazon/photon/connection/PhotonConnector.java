package software.amazon.photon.connection;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.protocol.SerializedCommand;
import software.amazon.photon.resp.RespCodec;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

/**
 * A thread-safe connection to the Photon cache with concurrent request support.
 *
 * <p>Handles TLS setup, AUTH, the {@code photon.config} handshake, and {@code photon.request}
 * command execution. Multiple threads can safely call {@link #request(PhotonRequest)} concurrently
 * — responses are correlated via FIFO position or deferred request ID.
 *
 * <p>Lifecycle: {@code connect()} → {@code authenticate(String, String)} → {@code
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
public class PhotonConnector implements Closeable {

    /** Default request timeout in milliseconds. */
    public static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    /** Default initialization handshake timeout in milliseconds. */
    public static final int DEFAULT_INITIALIZATION_TIMEOUT_MS = 5000;

    /** Default connection timeout in milliseconds. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;

    /** Default maximum pending requests. */
    public static final int DEFAULT_MAX_PENDING = 100;

    /** Maximum response size in bytes (16 MB). */
    public static final int MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

    private static final String[] PREFERRED_TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};
    private static final ScheduledThreadPoolExecutor TIMEOUT_EXECUTOR = createTimeoutExecutor();

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            Thread thread = new Thread(runnable, "photon-timeout-watchdog");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final int connectTimeoutMs;
    private final int initializationTimeoutMs;
    private final int requestTimeoutMs;
    private final int maxPending;

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
    public PhotonConnector(String host, int port, SSLContext sslContext) {
        this(
                host,
                port,
                sslContext,
                DEFAULT_CONNECT_TIMEOUT_MS,
                DEFAULT_INITIALIZATION_TIMEOUT_MS,
                DEFAULT_READ_TIMEOUT_MS,
                DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs read timeout in milliseconds
     */
    public PhotonConnector(
            String host, int port, SSLContext sslContext, int connectTimeoutMs, int readTimeoutMs) {
        this(
                host,
                port,
                sslContext,
                connectTimeoutMs,
                readTimeoutMs,
                readTimeoutMs,
                DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts and pending queue size.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs read timeout in milliseconds
     * @param maxPending maximum requests awaiting either a FIFO or a deferred reply
     */
    public PhotonConnector(
            String host,
            int port,
            SSLContext sslContext,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxPending) {
        this(host, port, sslContext, connectTimeoutMs, readTimeoutMs, readTimeoutMs, maxPending);
    }

    /**
     * Create a connection with independent initialization and request budgets.
     *
     * @param host the cache host
     * @param port the cache port
     * @param sslContext the SSL context
     * @param connectTimeoutMs TCP connection timeout in milliseconds
     * @param initializationTimeoutMs TLS, AUTH, and config timeout in milliseconds
     * @param requestTimeoutMs total timeout for each Photon request attempt
     * @param maxPending maximum requests awaiting either a FIFO or a deferred reply
     */
    public PhotonConnector(
            String host,
            int port,
            SSLContext sslContext,
            int connectTimeoutMs,
            int initializationTimeoutMs,
            int requestTimeoutMs,
            int maxPending) {
        requirePositive("connectTimeoutMs", connectTimeoutMs);
        requirePositive("initializationTimeoutMs", initializationTimeoutMs);
        requirePositive("requestTimeoutMs", requestTimeoutMs);
        requirePositive("maxPending", maxPending);
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.connectTimeoutMs = connectTimeoutMs;
        this.initializationTimeoutMs = initializationTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
        this.maxPending = maxPending;
    }

    // =========================================================================
    // Lifecycle: connect → authenticate → configure → request → close
    // =========================================================================

    /**
     * Establish the TCP + TLS connection.
     *
     * <p>After this returns, the socket is connected and TLS handshake is complete, but no Photon
     * protocol handshake has occurred. Call {@link #authenticate(String, String)} next.
     *
     * @throws PhotonConnectionError if the connection fails
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

            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                plainSocket = new Socket();
                plainSocket.setTcpNoDelay(true); // Disable Nagle's algorithm
                plainSocket.setKeepAlive(true);
                plainSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

                // createSocket takes ownership of plainSocket on success (autoClose=true).
                // Keep plainSocket reference until then for cleanup on failure.
                SSLSocket sslSocket = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
                plainSocket = null; // Transfer ownership succeeded

                socket = sslSocket;
                socket.setSoTimeout(initializationTimeoutMs);

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
                runWithDeadline(socket::startHandshake, initializationTimeoutMs, "TLS handshake");

                outputStream = socket.getOutputStream();

                // Initialize pending queue and reader (not started yet)
                pending = new PendingRequests(maxPending);
                reader =
                        new SocketReader(socket.getInputStream(), pending, this::markDead, MAX_RESPONSE_SIZE);
            } catch (IOException e) {
                closeSocketQuietly();
                throw new PhotonConnectionError(
                        "Failed to establish TLS connection to " + host + ":" + port, e);
            } catch (RuntimeException e) {
                // Catches IllegalArgumentException from setEnabledProtocols if protocols unsupported
                closeSocketQuietly();
                throw new PhotonConnectionError("Failed to configure TLS for " + host + ":" + port, e);
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
                throw new PhotonConnectionError("Connection closed during connect");
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
     * Authenticate with the cache using AUTH command.
     *
     * <p>Must be called after {@link #connect()} and before any requests. The reader thread is not
     * started yet, so this uses synchronous reads.
     *
     * @param userId the user ID for AUTH (e.g., "default.iam-user")
     * @param authToken the AUTH token/password
     * @throws PhotonConnectionError if AUTH fails
     */
    public void authenticate(String userId, String authToken) {
        ensureConnectedNotDead();
        if (readerThread != null) {
            throw new IllegalStateException(
                    "Reader already started - authenticate must be called before configureAndStartReader");
        }

        try {
            byte[] authCmd =
                    RespCodec.encodeCommand(
                            "AUTH",
                            userId.getBytes(StandardCharsets.UTF_8),
                            authToken.getBytes(StandardCharsets.UTF_8));
            sendRawDirect(authCmd);
            RespValue authReply = reader.readFrame();

            // Expect +OK
            if (!(authReply instanceof RespSimpleString)) {
                markDead();
                throw new PhotonConnectionError("AUTH failed: unexpected response type: " + authReply);
            }
            String ok = new String(((RespSimpleString) authReply).getValue(), StandardCharsets.UTF_8);
            if (!"OK".equals(ok)) {
                markDead();
                throw new PhotonConnectionError("AUTH failed: " + ok);
            }
        } catch (PhotonConnectionError e) {
            throw e;
        } catch (Exception e) {
            markDead();
            throw new PhotonConnectionError("AUTH handshake failed", e);
        }
    }

    /**
     * Perform the photon.config handshake and start the background reader thread.
     *
     * <p>After this returns, the connection is fully ready for concurrent requests.
     *
     * @param overrides optional configuration overrides (may be null or empty)
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if config handshake fails
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
            SerializedCommand configCmd = PhotonProtocol.serializeConfig(initialOverrides);
            byte[] encoded =
                    RespCodec.encodeCommand(
                            configCmd.getCommandName(), configCmd.getArgs().toArray(new byte[0][]));
            sendRawDirect(encoded);
            RespValue configReply = reader.readFrame();
            this.config = PhotonProtocol.parseConfigResponse(configReply);
        } catch (PhotonConnectionError e) {
            markDead();
            throw e;
        } catch (Exception e) {
            markDead();
            throw new PhotonConnectionError("Config handshake failed", e);
        }

        // Request timeouts are enforced per future below. A socket timeout here
        // would kill an otherwise healthy connection simply because it was idle.
        try {
            socket.setSoTimeout(0);
        } catch (IOException e) {
            markDead();
            throw new PhotonConnectionError("Failed to configure background reader", e);
        }

        // Start the reader thread
        startReaderThread();

        return this.config;
    }

    /**
     * Convenience: connect, authenticate, configure, and start reader in one call.
     *
     * @param userId the user ID for AUTH (e.g., "default.iam-user")
     * @param authToken the AUTH token/password
     * @param overrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(
            String userId, String authToken, Map<String, Object> overrides) {
        connect();
        authenticate(userId, authToken);
        return configureAndStartReader(overrides);
    }

    /**
     * Convenience: connect, authenticate with credentials provider, configure, and start reader.
     *
     * <p>The provider is called after TLS connect to get fresh credentials.
     *
     * @param authProvider supplies the AUTH credentials (called after TLS connect)
     * @param overrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(
            Supplier<AuthCredentials> authProvider, Map<String, Object> overrides) {
        connect();
        AuthCredentials creds;
        try {
            creds = authProvider.get();
        } catch (Exception e) {
            markDead();
            throw new PhotonConnectionError("Auth provider failed", e);
        }
        authenticate(creds.getUserId(), creds.getToken());
        return configureAndStartReader(overrides);
    }

    /** Convenience: connect and authenticate with empty config overrides. */
    public ResolvedConfig connectAndAuthenticate(String userId, String authToken) {
        return connectAndAuthenticate(userId, authToken, Collections.emptyMap());
    }

    /** Convenience: connect and authenticate with provider and empty config overrides. */
    public ResolvedConfig connectAndAuthenticate(Supplier<AuthCredentials> authProvider) {
        return connectAndAuthenticate(authProvider, Collections.emptyMap());
    }

    // =========================================================================
    // Request execution
    // =========================================================================

    /**
     * Send a {@code photon.request} and return the parsed response.
     *
     * <p>This method is thread-safe and can be called concurrently from multiple threads. Responses
     * are correlated by FIFO position or deferred request ID.
     *
     * @param request the request to send
     * @return the cache response
     * @throws PhotonConnectionLost if the connection dies
     * @throws PhotonTimeoutError if the request times out
     */
    public PhotonResponse request(PhotonRequest request) {
        return request(request, new PhotonRequestCancellation());
    }

    /**
     * Send a {@code photon.request} with an external cancellation signal.
     *
     * @param request the request to send
     * @param cancellation cancellation signal for this request
     * @return the cache response
     */
    public PhotonResponse request(PhotonRequest request, PhotonRequestCancellation cancellation) {
        ensureReady();

        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        byte[] encoded =
                RespCodec.encodeCommand(cmd.getCommandName(), cmd.getArgs().toArray(new byte[0][]));

        RespValue reply = sendAndAwait(encoded, cancellation, true, requestTimeoutMs);
        return PhotonProtocol.parseRequestResponse(reply);
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
        int timeoutMs =
                PhotonProtocol.CONFIG_COMMAND.equals(command) ? initializationTimeoutMs : requestTimeoutMs;
        return sendAndAwait(encoded, new PhotonRequestCancellation(), false, timeoutMs);
    }

    // =========================================================================
    // State queries
    // =========================================================================

    /** Get the resolved config from the last successful handshake. */
    public ResolvedConfig getConfig() {
        return config;
    }

    /** Check if the connection is usable (connected and not dead). */
    public boolean isConnected() {
        synchronized (stateLock) {
            return socket != null && !socket.isClosed() && !dead && !closed;
        }
    }

    /** Check if the connection is dead (failed and cannot be used). */
    public boolean isDead() {
        return dead;
    }

    /** Check if the connection is fully ready for requests (reader thread running). */
    public boolean isReady() {
        synchronized (stateLock) {
            return isConnected() && readerThread != null;
        }
    }

    /** Register a callback invoked once when this connector becomes unusable. */
    public void setOnDisconnect(Runnable onDisconnect) {
        this.onDisconnect = onDisconnect;
        if (dead) {
            notifyDisconnect();
        }
    }

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
                pending.failAll(new PhotonConnectionLost("Connection closed"));
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
        readerThread = new Thread(reader::runLoop, "photon-reader-" + host + ":" + port);
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
            throw new PhotonConnectionLost("Connection is dead");
        }
        synchronized (stateLock) {
            if (socket == null) {
                throw new PhotonConnectionLost("Connection is not established");
            }
            if (closed) {
                throw new PhotonConnectionLost("Connection is closed");
            }
        }
    }

    /** Ensure connection is fully ready for requests (reader thread running). */
    private void ensureReady() {
        ensureConnectedNotDead();
        synchronized (stateLock) {
            if (readerThread == null) {
                throw new PhotonConnectionLost(
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
            long deadlineNanos = deadlineAfter(initializationTimeoutMs);
            writeWithDeadline(outputStream, data, deadlineNanos, "Initialization write");
        } catch (PhotonTimeoutError e) {
            markDead();
            throw e;
        } catch (IOException e) {
            markDead();
            throw new PhotonConnectionLost("Failed to write to socket", e);
        }
    }

    /**
     * Register, send, and await response using FIFO correlation. Uses the send lock to ensure atomic
     * register+send.
     *
     * @param timeoutMs the budget for this attempt: {@code initializationTimeoutMs} for {@code
     *     photon.config}, {@code requestTimeoutMs} for everything else
     */
    private RespValue sendAndAwait(
            byte[] encoded,
            PhotonRequestCancellation cancellation,
            boolean deferredResponseAllowed,
            int timeoutMs) {
        if (cancellation.isCancelled()) {
            throw new PhotonRequestCancellation.CancelledException();
        }

        CompletableFuture<RespValue> future;
        long deadlineNanos = deadlineAfter(timeoutMs);

        boolean locked;
        try {
            locked = sendLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotonConnectionLost("Interrupted while waiting to send request", e);
        }
        if (!locked) {
            markDead();
            throw new PhotonTimeoutError("Timed out acquiring send lock after " + timeoutMs + "ms");
        }
        try {
            if (cancellation.isCancelled()) {
                throw new PhotonRequestCancellation.CancelledException();
            }

            // Must register before sending to maintain FIFO correlation
            future = pending.registerNewRequest(deferredResponseAllowed);

            // Snapshot outputStream under lock to avoid NPE race with concurrent close/markDead
            OutputStream out;
            synchronized (stateLock) {
                out = outputStream;
                if (out == null || dead) {
                    throw new PhotonConnectionLost("Connection closed during send");
                }
            }

            try {
                writeWithDeadline(out, encoded, deadlineNanos, "Request write");
            } catch (PhotonTimeoutError e) {
                markDead();
                pending.failAll(e);
                throw e;
            } catch (IOException e) {
                // Send failed - mark dead (future will be failed by markDead -> failAll)
                markDead();
                PhotonConnectionLost failure = new PhotonConnectionLost("Failed to write to socket", e);
                pending.failAll(failure);
                throw failure;
            }
        } finally {
            sendLock.unlock();
        }

        Runnable cancellationAction =
                () -> pending.cancel(future, new PhotonRequestCancellation.CancelledException());
        cancellation.register(cancellationAction);

        // Wait for response
        try {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                throw new TimeoutException();
            }
            return future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            PhotonTimeoutError timeout =
                    new PhotonTimeoutError("Photon request timed out after " + timeoutMs + "ms", e);
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
                        PhotonConnectionLost failure =
                                new PhotonConnectionLost("Request interrupted", interrupted);
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
            if (cause instanceof PhotonConnectionLost) {
                throw (PhotonConnectionLost) cause;
            } else if (cause instanceof PhotonTimeoutError) {
                throw (PhotonTimeoutError) cause;
            } else if (cause instanceof PhotonProtocolError) {
                throw (PhotonProtocolError) cause;
            } else if (cause instanceof PhotonError) {
                throw (PhotonError) cause;
            } else {
                throw new PhotonConnectionLost("Request failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markDead();
            pending.failAll(new PhotonConnectionLost("Request interrupted", e));
            throw new PhotonConnectionLost("Request interrupted", e);
        } finally {
            cancellation.clear(cancellationAction);
        }
    }

    private PhotonError requestFailure(Throwable cause) {
        if (cause instanceof PhotonError) {
            return (PhotonError) cause;
        }
        return new PhotonConnectionLost("Photon request failed before a response was received", cause);
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
            throw new PhotonTimeoutError(operation + " timed out");
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
                throw new PhotonTimeoutError(operation + " timed out", e);
            }
            throw e;
        } finally {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
            }
        }

        if (timedOut.get()) {
            throw new PhotonTimeoutError(operation + " timed out");
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
                throw new PhotonTimeoutError(description + " timed out after " + timeoutMs + "ms", e);
            }
            throw e;
        } finally {
            if (completed.compareAndSet(false, true)) {
                timeout.cancel(false);
            }
        }
        if (timedOut.get()) {
            throw new PhotonTimeoutError(description + " timed out after " + timeoutMs + "ms");
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
