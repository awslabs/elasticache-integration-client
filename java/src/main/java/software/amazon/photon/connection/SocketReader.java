package software.amazon.photon.connection;

import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.resp.DecodeResult;
import software.amazon.photon.resp.IncompleteFrame;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespCodec;
import software.amazon.photon.resp.RespInteger;
import software.amazon.photon.resp.RespPush;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Reads RESP frames and resolves pending futures by FIFO position or request ID.
 *
 * <p>This class manages the read buffer and frame decoding. It runs in a
 * dedicated reader thread (or can be used synchronously for single-threaded
 * connections). Complete frames are routed as positional replies, deferred
 * request IDs, or response pushes.
 *
 * <p>On any error (timeout, EOF, protocol error), it fails all pending
 * requests and signals the connection to close.
 */
public class SocketReader {

    private static final int READ_BUFFER_SIZE = 8192;
    private static final int DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024; // 16 MB

    private final InputStream inputStream;
    private final PendingRequests pending;
    private final int maxResponseSize;
    private final Runnable onDeath;

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final byte[] readBuf = new byte[READ_BUFFER_SIZE];

    // Track consumed position to avoid re-parsing entire buffer
    private int consumedOffset = 0;

    /**
     * Create a socket reader.
     *
     * @param inputStream the socket input stream
     * @param pending     the pending requests queue to resolve
     * @param onDeath     callback when the reader dies (for connection cleanup)
     */
    public SocketReader(InputStream inputStream, PendingRequests pending, Runnable onDeath) {
        this(inputStream, pending, onDeath, DEFAULT_MAX_RESPONSE_SIZE);
    }

    /**
     * Create a socket reader with custom max response size.
     *
     * @param inputStream     the socket input stream
     * @param pending         the pending requests queue to resolve
     * @param onDeath         callback when the reader dies
     * @param maxResponseSize maximum response size in bytes
     */
    public SocketReader(InputStream inputStream, PendingRequests pending,
                        Runnable onDeath, int maxResponseSize) {
        this.inputStream = inputStream;
        this.pending = pending;
        this.onDeath = onDeath;
        this.maxResponseSize = maxResponseSize;
    }

    /**
     * Run the reader loop. This blocks until the connection dies.
     *
     * <p>Call this from a dedicated thread. On exit (normal or exceptional),
     * the onDeath callback is invoked first (to set dead=true), then all
     * pending requests are failed.
     */
    public void runLoop() {
        try {
            while (true) {
                readAndResolve();
            }
        } catch (PhotonConnectionLost | PhotonTimeoutError | PhotonProtocolError e) {
            // Mark dead before failing futures so isDead() is true when callers observe the exception
            onDeath.run();
            pending.failAll(e);
        } catch (Exception e) {
            onDeath.run();
            pending.failAll(new PhotonConnectionLost("Reader thread failed", e));
        }
    }

    /**
     * Read a single RESP frame (blocking) and resolve the next pending future.
     *
     * <p>This can be called in a loop by a reader thread, or once per request
     * for synchronous use.
     *
     * @throws PhotonTimeoutError if the read times out
     * @throws PhotonConnectionLost if the socket closes or fails
     * @throws PhotonProtocolError if the response exceeds max size
     */
    public void readAndResolve() {
        RespValue value = readFrame();
        if (value instanceof RespPush) {
            handlePush((RespPush) value);
            return;
        }
        if (value instanceof RespSimpleString) {
            String text = new String(
                    ((RespSimpleString) value).getValue(), StandardCharsets.UTF_8);
            if (!"OK".equals(text) && pending.moveNextToAsyncResponses(text)) {
                return;
            }
        }
        boolean resolved = pending.resolveOldestRequest(value);
        if (!resolved) {
            // Unexpected response with no pending request - protocol error
            throw new PhotonProtocolError("Received response with no pending request");
        }
    }

    private void handlePush(RespPush push) {
        if (push.getElements().isEmpty()
                || !(push.getElements().get(0) instanceof RespBulkString)) {
            throw new PhotonProtocolError("Malformed RESP push payload");
        }

        String kind = new String(
                ((RespBulkString) push.getElements().get(0)).getValue(),
                StandardCharsets.UTF_8);
        if (!PhotonProtocol.PUSH_KIND_RESPONSE.equals(kind)) {
            return;
        }
        if (push.getElements().size() != 3) {
            throw new PhotonProtocolError("Malformed photon.response push payload");
        }
        if (!(push.getElements().get(1) instanceof RespInteger)) {
            throw new PhotonProtocolError("Malformed photon.response push request ID");
        }

        long requestId = ((RespInteger) push.getElements().get(1)).getValue();
        pending.resolveAsync(Long.toString(requestId), push.getElements().get(2));
    }

    /**
     * Read a single RESP frame synchronously.
     *
     * <p>Used during handshake when we're not yet in async mode.
     *
     * @return the decoded RESP value
     * @throws PhotonTimeoutError if the read times out
     * @throws PhotonConnectionLost if the socket closes or fails
     * @throws PhotonProtocolError if the response exceeds max size
     */
    public RespValue readFrame() {
        while (true) {
            // Try to decode from buffered data
            if (buffer.size() > consumedOffset) {
                byte[] data = buffer.toByteArray();
                try {
                    DecodeResult result = RespCodec.decode(data, consumedOffset);
                    // Successfully decoded - advance consumed offset
                    consumedOffset = result.getPosition();
                    // Compact buffer if we've consumed a lot
                    if (consumedOffset > READ_BUFFER_SIZE) {
                        compactBuffer();
                    }
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
                throw new PhotonTimeoutError("Read timed out", e);
            } catch (IOException e) {
                throw new PhotonConnectionLost("Socket read failed", e);
            }

            if (bytesRead < 0) {
                throw new PhotonConnectionLost("Connection closed by server (EOF)");
            }

            if (buffer.size() + bytesRead > maxResponseSize) {
                throw new PhotonProtocolError("Response exceeded maximum size of " + maxResponseSize + " bytes");
            }

            buffer.write(readBuf, 0, bytesRead);
        }
    }

    /**
     * Compact the buffer by removing already-consumed bytes.
     */
    private void compactBuffer() {
        byte[] data = buffer.toByteArray();
        buffer.reset();
        buffer.write(data, consumedOffset, data.length - consumedOffset);
        consumedOffset = 0;
    }

    /**
     * Clear the buffer. Used when resetting state.
     */
    public void reset() {
        buffer.reset();
        consumedOffset = 0;
    }
}
