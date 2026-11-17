// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.connection;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsTimeoutError;
import software.amazon.elasticache.integrations.protocol.ElastiCacheIntegrationsProtocol;
import software.amazon.elasticache.integrations.resp.DecodeResult;
import software.amazon.elasticache.integrations.resp.IncompleteFrame;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespCodec;
import software.amazon.elasticache.integrations.resp.RespPush;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/**
 * Reads RESP frames and resolves pending futures by FIFO position or request ID.
 *
 * <p>This class manages the read buffer and frame decoding. It runs in a dedicated reader thread
 * (or can be used synchronously for single-threaded connections). Complete frames are routed as
 * positional replies, deferred request IDs, or response pushes.
 *
 * <p>On any error (timeout, EOF, protocol error), it fails all pending requests and signals the
 * connection to close.
 */
public class SocketReader {

    /** Default number of bytes requested from the socket per read (64 KB). */
    public static final int DEFAULT_READ_SIZE = 64 * 1024;

    /** Default maximum response size in bytes (16 MB). */
    public static final int DEFAULT_MAX_RESPONSE_SIZE = 16 * 1024 * 1024;

    private final InputStream inputStream;
    private final PendingRequests pending;
    private final int maxResponseSize;
    private final int readSize;
    private final Runnable onDeath;

    // Unconsumed bytes are buffer[consumedOffset, length). The decoder reads the array in place,
    // so a frame arriving in many reads is not copied once per read.
    private byte[] buffer;
    private int length = 0;
    private int consumedOffset = 0;

    /**
     * Create a socket reader.
     *
     * @param inputStream the socket input stream
     * @param pending the pending requests queue to resolve
     * @param onDeath callback when the reader dies (for connection cleanup)
     */
    public SocketReader(InputStream inputStream, PendingRequests pending, Runnable onDeath) {
        this(inputStream, pending, onDeath, DEFAULT_MAX_RESPONSE_SIZE);
    }

    /**
     * Create a socket reader with custom max response size.
     *
     * @param inputStream the socket input stream
     * @param pending the pending requests queue to resolve
     * @param onDeath callback when the reader dies
     * @param maxResponseSize maximum response size in bytes
     */
    public SocketReader(
            InputStream inputStream, PendingRequests pending, Runnable onDeath, int maxResponseSize) {
        this(inputStream, pending, onDeath, maxResponseSize, DEFAULT_READ_SIZE);
    }

    /**
     * Create a socket reader with custom max response size and read size.
     *
     * @param inputStream the socket input stream
     * @param pending the pending requests queue to resolve
     * @param onDeath callback when the reader dies
     * @param maxResponseSize maximum response size in bytes
     * @param readSize most bytes to request from the socket per read
     * @throws IllegalArgumentException if readSize is not positive
     */
    public SocketReader(
            InputStream inputStream,
            PendingRequests pending,
            Runnable onDeath,
            int maxResponseSize,
            int readSize) {
        if (readSize <= 0) {
            throw new IllegalArgumentException("readSize must be positive: " + readSize);
        }
        this.inputStream = inputStream;
        this.pending = pending;
        this.onDeath = onDeath;
        this.maxResponseSize = maxResponseSize;
        this.readSize = readSize;
        this.buffer = new byte[readSize];
    }

    /**
     * Run the reader loop. This blocks until the connection dies.
     *
     * <p>Call this from a dedicated thread. On exit (normal or exceptional), the onDeath callback is
     * invoked first (to set dead=true), then all pending requests are failed.
     */
    public void runLoop() {
        try {
            while (true) {
                readAndResolve();
            }
        } catch (ElastiCacheIntegrationsConnectionLost | ElastiCacheIntegrationsTimeoutError e) {
            // Mark dead before failing futures so isDead() is true when callers observe the exception
            onDeath.run();
            pending.failAll(e);
        } catch (ElastiCacheIntegrationsProtocolError e) {
            // A malformed frame leaves the stream position untrustworthy: treat it as a lost
            // connection (not a retriable protocol issue) while preserving the parse failure as
            // its cause.
            onDeath.run();
            pending.failAll(new ElastiCacheIntegrationsConnectionLost("reply stream corrupt", e));
        } catch (Exception e) {
            onDeath.run();
            pending.failAll(new ElastiCacheIntegrationsConnectionLost("Reader thread failed", e));
        }
    }

    /**
     * Read a single RESP frame (blocking) and resolve the next pending future.
     *
     * <p>This can be called in a loop by a reader thread, or once per request for synchronous use.
     *
     * @throws ElastiCacheIntegrationsTimeoutError if the read times out
     * @throws ElastiCacheIntegrationsConnectionLost if the socket closes or fails
     * @throws ElastiCacheIntegrationsProtocolError if the response exceeds max size
     */
    public void readAndResolve() {
        RespValue value = readFrame();
        if (value instanceof RespPush) {
            handlePush((RespPush) value);
            return;
        }
        if (value instanceof RespSimpleString) {
            String text = decodeStrictUtf8(((RespSimpleString) value).getValue(), "async response ID");
            if (!"OK".equals(text) && pending.moveNextToAsyncResponses(text)) {
                return;
            }
        }
        boolean resolved = pending.resolveOldestRequest(value);
        if (!resolved) {
            // Unexpected response with no pending request - protocol error
            throw new ElastiCacheIntegrationsProtocolError("Received response with no pending request");
        }
    }

    /**
     * Route a RESP3 push frame.
     *
     * <p>A {@code aws.response} push carries three elements: the push kind, the deferred request ID,
     * and the response value for that request. The push kind may arrive as a bulk or simple string.
     * An unrecognized push kind is fatal protocol corruption: a client that does not understand a
     * push has no request to correlate it with, so the stream can no longer be trusted.
     */
    private void handlePush(RespPush push) {
        List<RespValue> elements = push.getElements();
        byte[] kindBytes;
        if (!elements.isEmpty() && elements.get(0) instanceof RespBulkString) {
            kindBytes = ((RespBulkString) elements.get(0)).getValue();
        } else if (!elements.isEmpty() && elements.get(0) instanceof RespSimpleString) {
            kindBytes = ((RespSimpleString) elements.get(0)).getValue();
        } else {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Malformed RESP push payload: expected a bulk or simple string push kind");
        }

        String kind = decodeStrictUtf8(kindBytes, "push kind");
        if (!ElastiCacheIntegrationsProtocol.PUSH_KIND_RESPONSE.equals(kind)) {
            throw new ElastiCacheIntegrationsProtocolError("Unknown RESP push kind: " + kind);
        }
        if (elements.size() != 3) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Malformed "
                            + ElastiCacheIntegrationsProtocol.PUSH_KIND_RESPONSE
                            + " push payload: expected 3 elements but got "
                            + elements.size());
        }
        if (!(elements.get(1) instanceof RespBulkString)) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Malformed "
                            + ElastiCacheIntegrationsProtocol.PUSH_KIND_RESPONSE
                            + " push request ID: expected a bulk string");
        }

        String requestId =
                decodeStrictUtf8(((RespBulkString) elements.get(1)).getValue(), "push request ID");
        pending.resolveAsync(requestId, elements.get(2));
    }

    /**
     * Strictly decode UTF-8, rejecting malformed or unmappable bytes.
     *
     * <p>A looser or lossy decoding could mis-correlate a deferred response with a different
     * in-flight request.
     */
    private static String decodeStrictUtf8(byte[] value, String field) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid UTF-8 in " + field, e);
        }
    }

    /**
     * Read a single RESP frame synchronously.
     *
     * <p>Used during handshake when we're not yet in async mode.
     *
     * @return the decoded RESP value
     * @throws ElastiCacheIntegrationsTimeoutError if the read times out
     * @throws ElastiCacheIntegrationsConnectionLost if the socket closes or fails
     * @throws ElastiCacheIntegrationsProtocolError if the response exceeds max size
     */
    public RespValue readFrame() {
        while (true) {
            if (length > consumedOffset) {
                try {
                    DecodeResult result = RespCodec.decode(buffer, consumedOffset, length);
                    // A frame that arrived whole in one read never reaches the check below.
                    if (result.getPosition() - consumedOffset > maxResponseSize) {
                        throw responseTooLarge();
                    }
                    consumedOffset = result.getPosition();
                    if (consumedOffset > readSize) {
                        compactBuffer();
                    }
                    return result.getValue();
                } catch (IncompleteFrame e) {
                    // Everything unconsumed is a prefix of one unfinished frame, so this caps a
                    // single reply, not the pipelined replies buffered with it.
                    if (length - consumedOffset > maxResponseSize) {
                        throw responseTooLarge();
                    }
                }
            }

            ensureCapacity(readSize);
            int bytesRead;
            try {
                bytesRead = inputStream.read(buffer, length, readSize);
            } catch (SocketTimeoutException e) {
                throw new ElastiCacheIntegrationsTimeoutError("Read timed out", e);
            } catch (IOException e) {
                throw new ElastiCacheIntegrationsConnectionLost("Socket read failed", e);
            }

            if (bytesRead < 0) {
                throw new ElastiCacheIntegrationsConnectionLost("Connection closed by server (EOF)");
            }

            length += bytesRead;
        }
    }

    private ElastiCacheIntegrationsProtocolError responseTooLarge() {
        return new ElastiCacheIntegrationsProtocolError(
                "Response exceeded maximum size of " + maxResponseSize + " bytes");
    }

    /** Grow geometrically so that appends are amortized O(1). */
    private void ensureCapacity(int extra) {
        if (length + extra > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(length + extra, buffer.length * 2));
        }
    }

    /**
     * Move the unconsumed bytes to the front, overwriting consumed ones. Safe because Java has no
     * view onto a byte[], so every decoded value is already a copy of it.
     */
    private void compactBuffer() {
        System.arraycopy(buffer, consumedOffset, buffer, 0, length - consumedOffset);
        length -= consumedOffset;
        consumedOffset = 0;
    }
}
