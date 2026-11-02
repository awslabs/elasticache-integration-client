package software.amazon.elasticache.integrations.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConnectionLost;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/** Unit tests for SocketReader. */
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

        InputStream input =
                new InputStream() {
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
        assertEquals(
                "HELLO", new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readFrame_eof_throwsElastiCacheIntegrationsConnectionLost() {
        // Empty stream (immediate EOF)
        InputStream input = new ByteArrayInputStream(new byte[0]);
        PendingRequests pending = new PendingRequests();
        SocketReader reader = new SocketReader(input, pending, () -> {});

        ElastiCacheIntegrationsConnectionLost e =
                assertThrows(ElastiCacheIntegrationsConnectionLost.class, reader::readFrame);
        assertTrue(e.getMessage().contains("EOF"));
    }

    @Test
    void readFrame_ioError_throwsElastiCacheIntegrationsConnectionLost() {
        InputStream input =
                new InputStream() {
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

        ElastiCacheIntegrationsConnectionLost e =
                assertThrows(ElastiCacheIntegrationsConnectionLost.class, reader::readFrame);
        assertTrue(e.getMessage().contains("read failed"));
    }

    @Test
    void readFrame_largeBulkFrameAcrossManyReads_keepsTrailingFrame() {
        byte[] body = new byte[100_000];
        Arrays.fill(body, (byte) 'a');
        body[body.length - 1] = 'b';
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        byte[] header = ("$" + body.length + "\r\n").getBytes(StandardCharsets.UTF_8);
        frame.write(header, 0, header.length);
        frame.write(body, 0, body.length);
        byte[] tail = "\r\n+next\r\n".getBytes(StandardCharsets.UTF_8);
        frame.write(tail, 0, tail.length);
        SocketReader reader =
                new SocketReader(
                        new ByteArrayInputStream(frame.toByteArray()), new PendingRequests(), () -> {});

        assertArrayEquals(body, ((RespBulkString) reader.readFrame()).getValue());
        assertEquals(
                "next",
                new String(((RespSimpleString) reader.readFrame()).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void readFrame_maxSizeAppliesPerFrameNotToPipelinedReplies() {
        // Twenty 13-byte frames arrive in one read; each is under the cap, their total is not.
        StringBuilder replies = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            replies.append("+0123456789\r\n");
        }
        SocketReader reader =
                new SocketReader(
                        new ByteArrayInputStream(replies.toString().getBytes(StandardCharsets.UTF_8)),
                        new PendingRequests(),
                        () -> {},
                        13);

        for (int i = 0; i < 20; i++) {
            assertEquals(
                    "0123456789",
                    new String(((RespSimpleString) reader.readFrame()).getValue(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void readFrame_frameOneByteOverMaxSize_throwsProtocolError() {
        byte[] data = "+0123456789\r\n".getBytes(StandardCharsets.UTF_8);
        SocketReader reader =
                new SocketReader(new ByteArrayInputStream(data), new PendingRequests(), () -> {}, 12);

        ElastiCacheIntegrationsProtocolError e =
                assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readFrame);
        assertTrue(e.getMessage().contains("exceeded maximum size"));
    }

    /** Records the {@code len} of every read so a test can see how much the reader asked for. */
    private static final class RecordingInputStream extends ByteArrayInputStream {
        final List<Integer> requestedLengths = new ArrayList<>();

        RecordingInputStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            requestedLengths.add(len);
            return super.read(b, off, len);
        }
    }

    @Test
    void readFrame_requests64KbPerReadByDefault() {
        RecordingInputStream input =
                new RecordingInputStream("+OK\r\n".getBytes(StandardCharsets.UTF_8));
        SocketReader reader = new SocketReader(input, new PendingRequests(), () -> {});

        reader.readFrame();

        assertEquals(Arrays.asList(65536), input.requestedLengths);
    }

    @Test
    void readFrame_requestsConfiguredReadSizeAndDecodesAcrossReads() {
        RecordingInputStream input =
                new RecordingInputStream("+0123456789\r\n".getBytes(StandardCharsets.UTF_8));
        SocketReader reader = new SocketReader(input, new PendingRequests(), () -> {}, 1024, 4);

        RespValue value = reader.readFrame();

        assertEquals(
                "0123456789", new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8));
        assertEquals(Arrays.asList(4, 4, 4, 4), input.requestedLengths);
    }

    @Test
    void constructor_rejectsNonPositiveReadSize() {
        InputStream input = new ByteArrayInputStream(new byte[0]);
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new SocketReader(input, new PendingRequests(), () -> {}, 1024, 0));
        assertEquals("readSize must be positive: 0", e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SocketReader(input, new PendingRequests(), () -> {}, 1024, -1));
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

        ElastiCacheIntegrationsProtocolError e =
                assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readFrame);
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
        ElastiCacheIntegrationsProtocolError e =
                assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readAndResolve);
        assertTrue(e.getMessage().contains("no pending request"));
    }

    @Test
    void readAndResolve_requestIdThenPush_resolvesDeferredFuture() {
        byte[] data =
                ("+42\r\n" + ">3\r\n$12\r\naws.response\r\n$2\r\n42\r\n-ERR origin failed\r\n")
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
        SocketReader reader =
                new SocketReader(
                        new ByteArrayInputStream("+OK\r\n".getBytes(StandardCharsets.UTF_8)),
                        pending,
                        () -> {});

        reader.readAndResolve();

        assertEquals(new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_fifoOnlySimpleString_remainsFifoReply() {
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest(false);
        SocketReader reader =
                new SocketReader(
                        new ByteArrayInputStream("+QUEUED\r\n".getBytes(StandardCharsets.UTF_8)),
                        pending,
                        () -> {});

        reader.readAndResolve();

        assertEquals(new RespSimpleString("QUEUED".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_unmatchedPushRequestId_isDropped() {
        // A well-formed aws.response push for an ID with no waiting caller (e.g. removed
        // earlier by a timeout) is silently dropped rather than treated as an error.
        byte[] data =
                (">3\r\n$12\r\naws.response\r\n$3\r\n999\r\n+ignored\r\n" + "+OK\r\n")
                        .getBytes(StandardCharsets.UTF_8);
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

        assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readAndResolve);
    }

    @Test
    void readAndResolve_simpleStringPushKind_resolvesDeferredFuture() {
        // The push kind may arrive as a RESP simple string rather than a bulk string.
        byte[] data =
                ("+42\r\n" + ">3\r\n+aws.response\r\n$2\r\n42\r\n+deferred\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        PendingRequests pending = new PendingRequests();
        CompletableFuture<RespValue> future = pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        reader.readAndResolve();
        assertFalse(future.isDone());

        reader.readAndResolve();
        assertEquals(new RespSimpleString("deferred".getBytes(StandardCharsets.UTF_8)), future.join());
    }

    @Test
    void readAndResolve_malformedPushes_throwProtocolError() {
        String[] frames = {
            ">0\r\n",
            ">1\r\n:123\r\n",
            ">2\r\n$12\r\naws.response\r\n$2\r\n42\r\n",
            // Request ID as a RESP integer (the obsolete shape) is malformed: it must be a
            // bulk string, matching the FIFO simple-string key it correlates with.
            ">3\r\n$12\r\naws.response\r\n:42\r\n+value\r\n"
        };

        for (String frame : frames) {
            PendingRequests pending = new PendingRequests();
            SocketReader reader =
                    new SocketReader(
                            new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8)), pending, () -> {});
            assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readAndResolve, frame);
        }
    }

    @Test
    void readAndResolve_invalidUtf8AsyncResponseId_throwsProtocolError() {
        // A FIFO simple-string request ID with invalid UTF-8 must be rejected rather than
        // lossily decoded, since a mangled key could mis-correlate with another request.
        byte[] data = new byte[] {'+', (byte) 0xff, '\r', '\n'};
        PendingRequests pending = new PendingRequests();
        pending.registerNewRequest();
        SocketReader reader = new SocketReader(new ByteArrayInputStream(data), pending, () -> {});

        assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readAndResolve);
    }

    @Test
    void readAndResolve_invalidUtf8PushRequestId_throwsProtocolError() throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(">3\r\n$12\r\naws.response\r\n$1\r\n".getBytes(StandardCharsets.UTF_8));
        frame.write(0xff);
        frame.write("\r\n+value\r\n".getBytes(StandardCharsets.UTF_8));

        PendingRequests pending = new PendingRequests();
        SocketReader reader =
                new SocketReader(new ByteArrayInputStream(frame.toByteArray()), pending, () -> {});

        assertThrows(ElastiCacheIntegrationsProtocolError.class, reader::readAndResolve);
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
        assertTrue(failure instanceof ElastiCacheIntegrationsConnectionLost);
        assertTrue(failure.getCause() instanceof ElastiCacheIntegrationsProtocolError);
    }

    @Test
    void runLoop_callsOnDeathOnError() throws Exception {
        // Stream that throws on first read
        InputStream input =
                new InputStream() {
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
        InputStream input =
                new InputStream() {
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
}
