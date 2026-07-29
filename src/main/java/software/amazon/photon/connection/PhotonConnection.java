package software.amazon.photon.connection;

import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.protocol.SerializedCommand;
import software.amazon.photon.resp.RespCodec;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A thread-safe connection to the Photon cache with concurrent request support.
 *
 * <p>Handles TLS setup, AUTH, the {@code photon.config} handshake, and
 * {@code photon.request} command execution. Multiple threads can safely call
 * {@link #request(PhotonRequest)} concurrently — responses are correlated via
 * a FIFO queue.
 *
 * <p>Lifecycle: {@code connect()} → {@code authenticate()} → {@code configureAndStartReader()}
 * → {@code request()} (repeated) → {@code close()}.
 *
 * <p>For simpler use, {@link #connectAndAuthenticate(String)} and
 * {@link #connectAndAuthenticate(Supplier, Map)} combine these steps.
 *
 * <h3>Thread Safety</h3>
 * <p>This connection is thread-safe once fully connected. The {@code request()}
 * method uses a send-lock to serialize writes while allowing concurrent callers.
 * A background reader thread resolves responses in FIFO order.
 *
 * <h3>Timeout Behavior</h3>
 * <p>When a read timeout occurs, the connection is closed and all pending
 * requests are failed. This is intentional: the FIFO protocol requires strict
 * ordering, and after a timeout we cannot reliably determine which request
 * the next response belongs to.
 */
public class PhotonConnection implements Closeable {

    /** Default read timeout in milliseconds. */
    public static final int DEFAULT_READ_TIMEOUT_MS = 5000;
    /** Default connection timeout in milliseconds. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    /** Default maximum pending requests. */
    public static final int DEFAULT_MAX_PENDING = 1000;
    /** Maximum response size in bytes (16 MB). */
    public static final int MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
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

    // Send lock - serializes register + write to maintain FIFO correlation
    private final Lock sendLock = new ReentrantLock();

    /**
     * Create a connection to the specified host and port using the given SSL context.
     *
     * @param host       the cache host
     * @param port       the cache port
     * @param sslContext the SSL context to use for TLS (allows custom trust stores for testing)
     */
    public PhotonConnection(String host, int port, SSLContext sslContext) {
        this(host, port, sslContext, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts.
     *
     * @param host             the cache host
     * @param port             the cache port
     * @param sslContext       the SSL context
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs    read timeout in milliseconds
     */
    public PhotonConnection(String host, int port, SSLContext sslContext,
                            int connectTimeoutMs, int readTimeoutMs) {
        this(host, port, sslContext, connectTimeoutMs, readTimeoutMs, DEFAULT_MAX_PENDING);
    }

    /**
     * Create a connection with custom timeouts and pending queue size.
     *
     * @param host             the cache host
     * @param port             the cache port
     * @param sslContext       the SSL context
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs    read timeout in milliseconds
     * @param maxPending       maximum concurrent pending requests
     */
    public PhotonConnection(String host, int port, SSLContext sslContext,
                            int connectTimeoutMs, int readTimeoutMs, int maxPending) {
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxPending = maxPending;
    }

    // =========================================================================
    // Lifecycle: connect → authenticate → configure → request → close
    // =========================================================================

    /**
     * Establish the TCP + TLS connection.
     *
     * <p>After this returns, the socket is connected and TLS handshake is complete,
     * but no Photon protocol handshake has occurred. Call {@link #authenticate(String)}
     * next.
     *
     * @throws PhotonConnectionError if the connection fails
     */
    public void connect() {
        synchronized (stateLock) {
            if (socket != null) {
                throw new IllegalStateException("Already connected");
            }
            if (closed) {
                throw new IllegalStateException("Connection was closed");
            }

            try {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                Socket plainSocket = new Socket();
                plainSocket.setTcpNoDelay(true); // Disable Nagle's algorithm
                plainSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);

                socket = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
                socket.setSoTimeout(readTimeoutMs);

                // Enforce TLS 1.2 minimum
                socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                socket.startHandshake();

                outputStream = socket.getOutputStream();

                // Initialize pending queue and reader (not started yet)
                pending = new PendingRequests(maxPending);
                reader = new SocketReader(socket.getInputStream(), pending, this::markDead, MAX_RESPONSE_SIZE);
            } catch (IOException e) {
                closeSocketQuietly();
                throw new PhotonConnectionError("Failed to establish TLS connection to " + host + ":" + port, e);
            }
        }

        // Check for close-during-connect race
        synchronized (stateLock) {
            if (closed) {
                closeSocketQuietly();
                throw new PhotonConnectionError("Connection closed during connect");
            }
        }
    }

    /**
     * Authenticate with the cache using AUTH command.
     *
     * <p>Must be called after {@link #connect()} and before any requests.
     * The reader thread is not started yet, so this uses synchronous reads.
     *
     * @param authToken the AUTH token/password
     * @throws PhotonConnectionError if AUTH fails
     */
    public void authenticate(String authToken) {
        ensureConnectedNotDead();
        if (readerThread != null) {
            throw new IllegalStateException("Reader already started - authenticate must be called before configureAndStartReader");
        }

        try {
            byte[] authCmd = RespCodec.encodeCommand("AUTH", authToken.getBytes(StandardCharsets.UTF_8));
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
     * @param configOverrides optional configuration overrides (may be null or empty)
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if config handshake fails
     */
    public ResolvedConfig configureAndStartReader(Map<String, Object> configOverrides) {
        ensureConnectedNotDead();
        if (readerThread != null) {
            throw new IllegalStateException("Reader already started");
        }

        // Send config (still synchronous - reader not running yet)
        try {
            SerializedCommand configCmd = PhotonProtocol.serializeConfig(configOverrides);
            byte[] encoded = RespCodec.encodeCommand(configCmd.getCommandName(),
                    configCmd.getArgs().toArray(new byte[0][]));
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

        // Start the reader thread
        startReaderThread();

        return this.config;
    }

    /**
     * Convenience: connect, authenticate, configure, and start reader in one call.
     *
     * @param authToken       the AUTH token/password
     * @param configOverrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(String authToken, Map<String, Object> configOverrides) {
        connect();
        authenticate(authToken);
        return configureAndStartReader(configOverrides);
    }

    /**
     * Convenience: connect, authenticate with provider, configure, and start reader.
     *
     * @param authProvider    supplies the AUTH token (called after TLS connect)
     * @param configOverrides optional configuration overrides
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if any step fails
     */
    public ResolvedConfig connectAndAuthenticate(Supplier<String> authProvider, Map<String, Object> configOverrides) {
        connect();
        authenticate(authProvider.get());
        return configureAndStartReader(configOverrides);
    }

    /**
     * Convenience: connect and authenticate with empty config overrides.
     */
    public ResolvedConfig connectAndAuthenticate(String authToken) {
        return connectAndAuthenticate(authToken, Collections.emptyMap());
    }

    /**
     * Convenience: connect and authenticate with provider and empty config overrides.
     */
    public ResolvedConfig connectAndAuthenticate(Supplier<String> authProvider) {
        return connectAndAuthenticate(authProvider, Collections.emptyMap());
    }

    // =========================================================================
    // Request execution
    // =========================================================================

    /**
     * Send a {@code photon.request} and return the parsed response.
     *
     * <p>This method is thread-safe and can be called concurrently from multiple
     * threads. Responses are correlated in FIFO order.
     *
     * @param request the request to send
     * @return the cache response
     * @throws ConnectionLost     if the connection dies
     * @throws PhotonTimeoutError if the read times out (connection is then closed)
     */
    public PhotonResponse request(PhotonRequest request) {
        ensureConnectedNotDead();

        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        byte[] encoded = RespCodec.encodeCommand(cmd.getCommandName(),
                cmd.getArgs().toArray(new byte[0][]));

        RespValue reply = sendAndAwait(encoded);
        return PhotonProtocol.parseRequestResponse(reply);
    }

    /**
     * Send a raw command and return the raw RESP response.
     *
     * <p>Useful for extending with additional commands. Thread-safe.
     *
     * @param command the command name
     * @param args    command arguments
     * @return the RESP response
     */
    public RespValue sendCommand(String command, byte[]... args) {
        ensureConnectedNotDead();
        byte[] encoded = RespCodec.encodeCommand(command, args);
        return sendAndAwait(encoded);
    }

    // =========================================================================
    // State queries
    // =========================================================================

    /**
     * Get the resolved config from the last successful handshake.
     */
    public ResolvedConfig getConfig() {
        return config;
    }

    /**
     * Check if the connection is usable (connected and not dead).
     */
    public boolean isConnected() {
        synchronized (stateLock) {
            return socket != null && !socket.isClosed() && !dead && !closed;
        }
    }

    /**
     * Check if the connection is dead (failed and cannot be used).
     */
    public boolean isDead() {
        return dead;
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
                pending.failAll(new ConnectionLost("Connection closed"));
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

    /**
     * Mark the connection as dead. Called by the reader thread on any error.
     * Idempotent.
     */
    private void markDead() {
        synchronized (stateLock) {
            if (dead) {
                return;
            }
            dead = true;
            closeSocketQuietly();
        }
    }

    private void ensureConnectedNotDead() {
        if (dead) {
            throw new ConnectionLost("Connection is dead");
        }
        synchronized (stateLock) {
            if (socket == null) {
                throw new ConnectionLost("Connection is not established");
            }
            if (closed) {
                throw new ConnectionLost("Connection is closed");
            }
        }
    }

    /**
     * Send raw bytes directly, without FIFO correlation.
     * Used during handshake before the reader thread is started.
     */
    private void sendRawDirect(byte[] data) {
        try {
            outputStream.write(data);
            outputStream.flush();
        } catch (IOException e) {
            markDead();
            throw new ConnectionLost("Failed to write to socket", e);
        }
    }

    /**
     * Register, send, and await response using FIFO correlation.
     * Uses the send lock to ensure atomic register+send.
     */
    private RespValue sendAndAwait(byte[] encoded) {
        CompletableFuture<RespValue> future;

        sendLock.lock();
        try {
            // Must register before sending to maintain FIFO correlation
            future = pending.register();
            try {
                outputStream.write(encoded);
                outputStream.flush();
            } catch (IOException e) {
                // Send failed - remove from pending and mark dead
                // The future will be failed by markDead -> failAll
                markDead();
                throw new ConnectionLost("Failed to write to socket", e);
            }
        } finally {
            sendLock.unlock();
        }

        // Wait for response
        try {
            return future.get(readTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Timeout waiting for response - connection must die for FIFO safety
            markDead();
            pending.failAll(new PhotonTimeoutError("Read timed out after " + readTimeoutMs + "ms"));
            throw new PhotonTimeoutError("Read timed out after " + readTimeoutMs + "ms", e);
        } catch (ExecutionException e) {
            // Reader thread failed the future
            Throwable cause = e.getCause();
            if (cause instanceof ConnectionLost) {
                throw (ConnectionLost) cause;
            } else if (cause instanceof PhotonTimeoutError) {
                throw (PhotonTimeoutError) cause;
            } else {
                throw new ConnectionLost("Request failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectionLost("Request interrupted", e);
        }
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
