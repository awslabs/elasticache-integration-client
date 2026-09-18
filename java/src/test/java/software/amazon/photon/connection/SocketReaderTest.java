package software.amazon.photon.connection;

import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.PhotonConnectionLost;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespError;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SocketReader.
 */
class SocketReaderTest {

    @Test
    void readFrame_singleCompleteFrame() {
        // RESP simple string: +OK\r\n
        byte[] data = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        RespValue value = reader.readFrame();

        assertNotNull(value);
        assertTrue(value instanceof RespSimpleString);
        assertEquals("OK", new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readFrame_multipleFrames() {
        // Two simple strings
        byte[] data = "+FIRST\r\n+SECOND\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        RespValue v1 = reader.readFrame();
        RespValue v2 = reader.readFrame();

        assertEquals("FIRST", new String(((RespSimpleString) v1).getValue(), StandardCharsets.UTF_8));
        assertEquals("SECOND", new String(((RespSimpleString) v2).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readFrame_accumulatesPartialFrames() {
        // Simulate data arriving in chunks
        byte[] chunk1 = "+HEL".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "LO\r\n".getBytes(StandardCharsets.UTF_8);

        InputStream input = new InputStream() {
            int chunk = 0;
            int pos = 0;

            @Override
            public int read() throws IOException {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (chunk == 0) {
                    int toCopy = Math.min(len, chunk1.length - pos);
                    System.arraycopy(chunk1, pos, b, off, toCopy);
                    pos += toCopy;
                    if (pos >= chunk1.length) {
                        chunk = 1;
                        pos = 0;
                    }
                    return toCopy;
                } else if (chunk == 1) {
                    int toCopy = Math.min(len, chunk2.length - pos);
                    System.arraycopy(chunk2, pos, b, off, toCopy);
                    pos += toCopy;
                    return toCopy;
                }
                return -1;
            }
        };

        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        RespValue value = reader.readFrame();
        assertEquals("HELLO", new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readFrame_eof_throwsPhotonConnectionLost() {
        // Empty stream (immediate EOF)
        InputStream input = new ByteArrayInputStream(new byte[0]);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        PhotonConnectionLost e = assertThrows(PhotonConnectionLost.class, reader::readFrame);
        assertTrue(e.getMessage().contains("EOF"));
    }

    @Test
    void readFrame_ioError_throwsPhotonConnectionLost() {
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Simulated failure");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Simulated failure");
            }
        };

        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        PhotonConnectionLost e = assertThrows(PhotonConnectionLost.class, reader::readFrame);
        assertTrue(e.getMessage().contains("read failed"));
    }

    @Test
    void readFrame_exceedsMaxSize_throwsProtocolError() {
        // Create data larger than max size
        int maxSize = 100;
        StringBuilder sb = new StringBuilder("+");
        for (int i = 0; i < maxSize + 50; i++) {
            sb.append('X');
        }
        sb.append("\r\n");
        byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);

        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {}, maxSize);

        PhotonProtocolError e = assertThrows(PhotonProtocolError.class, reader::readFrame);
        assertTrue(e.getMessage().contains("exceeded maximum size"));
    }

    @Test
    void readAndResolve_resolvesPendingFuture() {
        byte[] data = "$5\r\nVALUE\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        // Register a pending request
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        assertFalse(future.isDone());

        // Read and resolve
        reader.readAndResolve();

        assertTrue(future.isDone());
        RespValue value = future.join();
        assertEquals("VALUE", new String(((RespBulkString) value).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readAndResolve_noPending_throwsProtocolError() {
        byte[] data = "$6\r\nORPHAN\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        // No pending request registered
        PhotonProtocolError e = assertThrows(PhotonProtocolError.class, reader::readAndResolve);
        assertTrue(e.getMessage().contains("no pending request"));
    }

    @Test
    void readAndResolve_requestIdThenPush_resolvesDeferredFuture() {
        byte[] data = ("+42\r\n"
                + ">3\r\n$15\r\nphoton.response\r\n$2\r\n42\r\n-ERR origin failed\r\n")
                .getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        reader.readAndResolve();
        assertFalse(future.isDone());
        assertEquals(0, pending.size());

        reader.readAndResolve();
        assertEquals(new RespError("ERR", "origin failed"), future.join());
    }

    @Test
    void readAndResolve_okSimpleString_remainsFifoReply() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(
                new ByteArrayInputStream("+OK\r\n".getBytes(StandardCharsets.UTF_8)),
                pending, () -> {});

        reader.readAndResolve();

        assertEquals(new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_fifoOnlySimpleString_remainsFifoReply() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest(false);
        SocketReader reader = new SocketReader(
                new ByteArrayInputStream("+QUEUED\r\n".getBytes(StandardCharsets.UTF_8)),
                pending, () -> {});

        reader.readAndResolve();

        assertEquals(
                new RespSimpleString("QUEUED".getBytes(StandardCharsets.UTF_8)),
                future.join());
    }

    @Test
    void readAndResolve_unmatchedPushRequestId_isDropped() {
        // A well-formed photon.response push for an ID with no waiting caller (e.g. removed
        // earlier by a timeout) is silently dropped rather than treated as an error.
        byte[] data = (">3\r\n$15\r\nphoton.response\r\n$3\r\n999\r\n+ignored\r\n"
                + "+OK\r\n").getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        reader.readAndResolve();
        assertFalse(future.isDone());

        reader.readAndResolve();
        assertEquals(new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_unknownPushKind_throwsProtocolError() {
        // An unrecognized push kind means the stream can no longer be correlated, so it is
        // fatal rather than ignored for forward compatibility.
        byte[] data = ">2\r\n$10\r\nother.kind\r\n:1\r\n".getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        assertThrows(PhotonProtocolError.class, reader::readAndResolve);
    }

    @Test
    void readAndResolve_simpleStringPushKind_resolvesDeferredFuture() {
        // The push kind may arrive as a RESP simple string rather than a bulk string.
        byte[] data = ("+42\r\n"
                + ">3\r\n+photon.response\r\n$2\r\n42\r\n+deferred\r\n")
                .getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        reader.readAndResolve();
        assertFalse(future.isDone());

        reader.readAndResolve();
        assertEquals(
                new RespSimpleString("deferred".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_malformedPushes_throwProtocolError() {
        String[] frames = {
                ">0\r\n",
                ">1\r\n:123\r\n",
                ">2\r\n$15\r\nphoton.response\r\n$2\r\n42\r\n",
                // Request ID as a RESP integer (the obsolete shape) is malformed: it must be a
                // bulk string, matching the FIFO simple-string key it correlates with.
                ">3\r\n$15\r\nphoton.response\r\n:42\r\n+value\r\n"
        };

        for (String frame : frames) {
            PendingRequests pending = new PendingRequests();
            SocketReader reader = new SocketReader(
                    new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)),
                    pending, () -> {});
            assertThrows(PhotonProtocolError.class, reader::readAndResolve, frame);
        }
    }

    @Test
    void readAndResolve_invalidUtf8AsyncResponseId_throwsProtocolError() {
        // A FIFO simple-string request ID with invalid UTF-8 must be rejected rather than
        // lossily decoded, since a mangled key could mis-correlate with another request.
        byte[] data = new byte[]{'+', (byte) 0xff, '\r', '\n'};
        PendingRequests pending = new PendingRequests();
        pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        assertThrows(PhotonProtocolError.class, reader::readAndResolve);
    }

    @Test
    void readAndResolve_invalidUtf8PushRequestId_throwsProtocolError() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(">3\r\n$15\r\nphoton.response\r\n$1\r\n".getBytes(StandardCharsets.UTF_8));
        frame.write(0xff);
        frame.write("\r\n+value\r\n".getBytes(StandardCharsets.UTF_8));

        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(
                new ByteArrayInputStream(frame.toByteArray()), pending, () -> {});

        assertThrows(PhotonProtocolError.class, reader::readAndResolve);
    }

    @Test
    void runLoop_wrapsProtocolErrorAsConnectionLost() throws Exception {
        // A malformed push means the read position can no longer be trusted: the reader loop
        // must treat this as a lost connection (with the parse failure preserved as cause),
        // not surface the raw protocol error to callers.
        byte[] data = ">0\r\n".getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        Thread t = new Thread(reader::runLoop);
        t.start();
        t.join(1000);

        assertTrue(future.isCompletedExceptionally());
        Throwable failure = future.handle((v, e) -> e).join();
        assertTrue(failure instanceof PhotonConnectionLost);
        assertTrue(failure.getCause() instanceof PhotonProtocolError);
    }

    @Test
    void runLoop_callsOnDeathOnError() throws Exception {
        // Stream that throws on first read
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection failed");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection failed");
            }
        };

        PendingRequests pending = new PendingRequests();
        AtomicBoolean deathCalled = new AtomicBoolean(false);
        SocketReader reader = new SocketReader(input, pending, () -> deathCalled.set(true));

        // Run loop in a thread
        Thread t = new Thread(reader::runLoop);
        t.start();
        t.join(1000);

        assertTrue(deathCalled.get());
        assertTrue(pending.isClosed());
    }

    @Test
    void runLoop_failsAllPendingOnError() throws Exception {
        InputStream input = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Connection failed");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new IOException("Connection failed");
            }
        };

        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> f1 = pending.registerNewRequest();
        CompletableFuture<RespValue> f2 = pending.registerNewRequest();

        SocketReader reader = new SocketReader(input, pending, () -> {});

        Thread t = new Thread(reader::runLoop);
        t.start();
        t.join(1000);

        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
    }

    @Test
    void reset_clearsBuffer() {
        // Verify that reset() clears internal state without throwing.
        // The primary use case is during error recovery in handshake flows.
        byte[] data = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(data);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        // Read a frame to populate internal state
        RespValue v1 = reader.readFrame();
        assertEquals("OK", new String(((RespSimpleString) v1).getValue(), StandardCharsets.UTF_8));

        // Reset clears the buffer and consumed offset
        reader.reset();

        // Calling reset multiple times is safe
        assertDoesNotThrow(() -> reader.reset());
    }
}
