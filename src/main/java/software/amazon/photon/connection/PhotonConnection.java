package software.amazon.photon.connection;

import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.protocol.SerializedCommand;
import software.amazon.photon.resp.DecodeResult;
import software.amazon.photon.resp.IncompleteFrame;
import software.amazon.photon.resp.RespCodec;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * A single synchronous connection to the Photon cache.
 *
 * <p>Handles TLS setup, AUTH, the {@code photon.config} handshake, and
 * {@code photon.request} command execution. Not thread-safe — intended to be
 * used from a single thread or protected externally.
 *
 * <p>Lifecycle: {@code connect()} → {@code request()} (repeated) → {@code close()}.
 */
public class PhotonConnection implements Closeable {

    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_BUFFER_SIZE = 8192;

    private final String host;
    private final int port;
    private final SSLContext sslContext;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private SSLSocket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private ResolvedConfig config;

    /**
     * Create a connection to the specified host and port using the given SSL context.
     *
     * @param host       the cache host
     * @param port       the cache port
     * @param sslContext the SSL context to use for TLS (allows custom trust stores for testing)
     */
    public PhotonConnection(String host, int port, SSLContext sslContext) {
        this(host, port, sslContext, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
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
        this.host = host;
        this.port = port;
        this.sslContext = sslContext;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    /**
     * Establish the TLS connection, authenticate, and perform the config handshake.
     *
     * @param authToken      the AUTH token/password
     * @param configOverrides optional configuration overrides (may be null or empty)
     * @return the resolved configuration from the cache
     * @throws PhotonConnectionError if the connection or handshake fails
     */
    public ResolvedConfig connect(String authToken, Map<String, Object> configOverrides) {
        try {
            SSLSocketFactory factory = sslContext.getSocketFactory();
            Socket plainSocket = new Socket();
            plainSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
            socket.setSoTimeout(readTimeoutMs);
            socket.startHandshake();

            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
        } catch (IOException e) {
            throw new PhotonConnectionError("Failed to establish TLS connection to " + host + ":" + port, e);
        }

        // AUTH
        try {
            byte[] authCmd = RespCodec.encodeCommand("AUTH", authToken.getBytes(StandardCharsets.UTF_8));
            sendRaw(authCmd);
            RespValue authReply = readResponse();
            // Expect +OK
            if (!(authReply instanceof RespSimpleString)) {
                throw new PhotonConnectionError("AUTH failed: unexpected response type: " + authReply);
            }
            String ok = new String(((RespSimpleString) authReply).getValue(), StandardCharsets.UTF_8);
            if (!"OK".equals(ok)) {
                throw new PhotonConnectionError("AUTH failed: " + ok);
            }
        } catch (PhotonConnectionError e) {
            throw e;
        } catch (Exception e) {
            throw new PhotonConnectionError("AUTH handshake failed", e);
        }

        // photon.config
        try {
            SerializedCommand configCmd = PhotonProtocol.serializeConfig(configOverrides);
            byte[] encoded = RespCodec.encodeCommand(configCmd.getCommandName(),
                    configCmd.getArgs().toArray(new byte[0][]));
            sendRaw(encoded);
            RespValue configReply = readResponse();
            this.config = PhotonProtocol.parseConfigResponse(configReply);
            return this.config;
        } catch (PhotonConnectionError e) {
            throw e;
        } catch (Exception e) {
            throw new PhotonConnectionError("Config handshake failed", e);
        }
    }

    /**
     * Convenience overload that connects with empty config overrides.
     */
    public ResolvedConfig connect(String authToken) {
        return connect(authToken, Collections.emptyMap());
    }

    /**
     * Send a {@code photon.request} and return the parsed response.
     *
     * @param request the request to send
     * @return the cache response
     * @throws ConnectionLost     if the socket dies mid-flight
     * @throws PhotonTimeoutError if the read times out
     */
    public PhotonResponse request(PhotonRequest request) {
        ensureConnected();

        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        byte[] encoded = RespCodec.encodeCommand(cmd.getCommandName(),
                cmd.getArgs().toArray(new byte[0][]));

        try {
            sendRaw(encoded);
        } catch (ConnectionLost e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectionLost("Failed to send request", e);
        }

        RespValue reply;
        try {
            reply = readResponse();
        } catch (PhotonTimeoutError e) {
            throw e;
        } catch (ConnectionLost e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectionLost("Failed to read response", e);
        }

        return PhotonProtocol.parseRequestResponse(reply);
    }

    /**
     * Send a raw command and return the raw RESP response.
     * Useful for extending with additional commands.
     */
    public RespValue sendCommand(String command, byte[]... args) {
        ensureConnected();
        byte[] encoded = RespCodec.encodeCommand(command, args);
        sendRaw(encoded);
        return readResponse();
    }

    /**
     * Get the resolved config from the last successful handshake.
     */
    public ResolvedConfig getConfig() {
        return config;
    }

    /**
     * Check if the connection is still open.
     */
    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    @Override
    public void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Best-effort close
            }
            socket = null;
            outputStream = null;
            inputStream = null;
        }
    }

    // --- Private helpers ---

    private void ensureConnected() {
        if (!isConnected()) {
            throw new ConnectionLost("Connection is not established or has been closed");
        }
    }

    private void sendRaw(byte[] data) {
        try {
            outputStream.write(data);
            outputStream.flush();
        } catch (IOException e) {
            throw new ConnectionLost("Failed to write to socket", e);
        }
    }

    private RespValue readResponse() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] readBuf = new byte[READ_BUFFER_SIZE];

        while (true) {
            // Try to decode from what we have
            if (buffer.size() > 0) {
                byte[] data = buffer.toByteArray();
                try {
                    DecodeResult result = RespCodec.decode(data, 0);
                    return result.getValue();
                } catch (IncompleteFrame e) {
                    // Need more data, fall through to read
                }
            }

            // Read more data from the socket
            int bytesRead;
            try {
                bytesRead = inputStream.read(readBuf);
            } catch (SocketTimeoutException e) {
                throw new PhotonTimeoutError("Read timed out after " + readTimeoutMs + "ms", e);
            } catch (IOException e) {
                throw new ConnectionLost("Socket read failed", e);
            }

            if (bytesRead < 0) {
                throw new ConnectionLost("Connection closed by server (EOF)");
            }

            buffer.write(readBuf, 0, bytesRead);
        }
    }
}
