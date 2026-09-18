package software.amazon.photon.connection;

import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.exception.PhotonTimeoutError;
import software.amazon.photon.protocol.PhotonProtocol;
import software.amazon.photon.resp.DecodeResult;
import software.amazon.photon.resp.IncompleteFrame;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespCodec;
import software.amazon.photon.resp.RespPush;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        } catch (PhotonConnectionLost | PhotonTimeoutError e) {
            // Mark dead before failing futures so isDead() is true when callers observe the exception
            onDeath.run();
            pending.failAll(e);
        } catch (PhotonProtocolError e) {
            // A malformed frame leaves the stream position untrustworthy: treat it as a lost
            // connection (not a retriable protocol issue) while preserving the parse failure as
            // its cause.
            onDeath.run();
            pending.failAll(new PhotonConnectionLost("reply stream corrupt", e));
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
            String text = decodeStrictUtf8(
                    ((RespSimpleString) value).getValue(), "async response ID");
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

    /**
     * Route a RESP3 push frame.
     *
     * <p>A {@code photon.response} push carries three elements: the push kind,
     * the deferred request ID, and the response value for that request. The
     * push kind may arrive as a bulk or simple string. An unrecognized push
     * kind is fatal protocol corruption: a client that does not understand a
     * push has no request to correlate it with, so the stream can no longer
     * be trusted.
     */
    private void handlePush(RespPush push) {
        List<RespValue> elements = push.getElements();
        byte[] kindBytes;
        if (!elements.isEmpty() && elements.get(0) instanceof RespBulkString) {
            kindBytes = ((RespBulkString) elements.get(0)).getValue();
        } else if (!elements.isEmpty() && elements.get(0) instanceof RespSimpleString) {
            kindBytes = ((RespSimpleString) elements.get(0)).getValue();
        } else {
            throw new PhotonProtocolError(
                    "Malformed RESP push payload: expected a bulk or simple string push kind");
        }

        String kind = decodeStrictUtf8(kindBytes, "push kind");
        if (!PhotonProtocol.PUSH_KIND_RESPONSE.equals(kind)) {
            throw new PhotonProtocolError("Unknown RESP push kind: " + kind);
        }
        if (elements.size() != 3) {
            throw new PhotonProtocolError("Malformed " + PhotonProtocol.PUSH_KIND_RESPONSE
                    + " push payload: expected 3 elements but got " + elements.size());
        }
        if (!(elements.get(1) instanceof RespBulkString)) {
            throw new PhotonProtocolError("Malformed " + PhotonProtocol.PUSH_KIND_RESPONSE
                    + " push request ID: expected a bulk string");
        }

        String requestId = decodeStrictUtf8(
                ((RespBulkString) elements.get(1)).getValue(), "push request ID");
        pending.resolveAsync(requestId, elements.get(2));
    }

    /**
     * Strictly decode UTF-8, rejecting malformed or unmappable bytes.
     *
     * <p>A looser or lossy decoding could mis-correlate a deferred response
     * with a different in-flight request.
     */
    private static String decodeStrictUtf8(byte[] value, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PhotonProtocolError("Invalid UTF-8 in " + field, e);
        }
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
