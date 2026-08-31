package software.amazon.photon.resp;

import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.PhotonProtocolError;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RespCodecTest {

    @Test
    void incompleteFrameCanonicalTypePreservesLegacyCatchCompatibility() {
        assertTrue(IncompleteFrame.class.isAssignableFrom(IncompleteFrameError.class));
    }

    // === Encode tests ===

    @Test
    void encodeCommand_simpleCommand() {
        byte[] result = RespCodec.encodeCommand("PING");
        String expected = "*1\r\n$4\r\nPING\r\n";
        assertEquals(expected, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void encodeCommand_withArgs() {
        byte[] result = RespCodec.encodeCommand("SET",
                "key".getBytes(StandardCharsets.UTF_8),
                "value".getBytes(StandardCharsets.UTF_8));
        String expected = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n";
        assertEquals(expected, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void encodeCommand_emptyArg() {
        byte[] result = RespCodec.encodeCommand("CMD", new byte[0]);
        String expected = "*2\r\n$3\r\nCMD\r\n$0\r\n\r\n";
        assertEquals(expected, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void encodeCommand_nullCommand_throws() {
        assertThrows(IllegalArgumentException.class, () -> RespCodec.encodeCommand(null));
    }

    @Test
    void encodeCommand_nullArg_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                RespCodec.encodeCommand("CMD", (byte[]) null));
    }

    // === Decode tests - Simple String ===

    @Test
    void decode_simpleString() throws IncompleteFrameError {
        byte[] data = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespSimpleString);
        assertArrayEquals("OK".getBytes(), ((RespSimpleString) result.getValue()).getValue());
        assertEquals(data.length, result.getPosition());
    }

    @Test
    void decode_simpleString_empty() throws IncompleteFrameError {
        byte[] data = "+\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespSimpleString);
        assertArrayEquals(new byte[0], ((RespSimpleString) result.getValue()).getValue());
    }

    // === Decode tests - Integer ===

    @Test
    void decode_integer() throws IncompleteFrameError {
        byte[] data = ":42\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(42, ((RespInteger) result.getValue()).getValue());
    }

    @Test
    void decode_negativeInteger() throws IncompleteFrameError {
        byte[] data = ":-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(-1, ((RespInteger) result.getValue()).getValue());
    }

    @Test
    void decode_zero() throws IncompleteFrameError {
        byte[] data = ":0\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(0, ((RespInteger) result.getValue()).getValue());
    }

    // === Decode tests - Error ===

    @Test
    void decode_error() throws IncompleteFrameError {
        byte[] data = "-ERR something went wrong\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespError);
        RespError error = (RespError) result.getValue();
        assertEquals("ERR", error.getCode());
        assertEquals("something went wrong", error.getMessage());
    }

    @Test
    void decode_error_noMessage() throws IncompleteFrameError {
        byte[] data = "-ERR\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespError);
        RespError error = (RespError) result.getValue();
        assertEquals("ERR", error.getCode());
        assertEquals("", error.getMessage());
    }

    // === Decode tests - Bulk String ===

    @Test
    void decode_bulkString() throws IncompleteFrameError {
        byte[] data = "$5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespBulkString);
        assertArrayEquals("hello".getBytes(), ((RespBulkString) result.getValue()).getValue());
    }

    @Test
    void decode_bulkString_empty() throws IncompleteFrameError {
        byte[] data = "$0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespBulkString);
        assertArrayEquals(new byte[0], ((RespBulkString) result.getValue()).getValue());
    }

    @Test
    void decode_nullBulkString() throws IncompleteFrameError {
        byte[] data = "$-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespNull);
    }

    // === Decode tests - Array ===

    @Test
    void decode_array() throws IncompleteFrameError {
        byte[] data = "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(2, array.getElements().size());
        assertArrayEquals("foo".getBytes(), ((RespBulkString) array.getElements().get(0)).getValue());
        assertArrayEquals("bar".getBytes(), ((RespBulkString) array.getElements().get(1)).getValue());
    }

    @Test
    void decode_emptyArray() throws IncompleteFrameError {
        byte[] data = "*0\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(0, array.getElements().size());
    }

    @Test
    void decode_nullArray() throws IncompleteFrameError {
        byte[] data = "*-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespNull);
    }

    @Test
    void decode_nestedArray() throws IncompleteFrameError {
        byte[] data = "*2\r\n*1\r\n:1\r\n*1\r\n:2\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray outer = (RespArray) result.getValue();
        assertEquals(2, outer.getElements().size());
        assertTrue(outer.getElements().get(0) instanceof RespArray);
        assertTrue(outer.getElements().get(1) instanceof RespArray);
    }

    @Test
    void decode_mixedArray() throws IncompleteFrameError {
        byte[] data = "*3\r\n:1\r\n$5\r\nhello\r\n+OK\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(3, array.getElements().size());
        assertTrue(array.getElements().get(0) instanceof RespInteger);
        assertTrue(array.getElements().get(1) instanceof RespBulkString);
        assertTrue(array.getElements().get(2) instanceof RespSimpleString);
    }

    // === Decode tests - Push (>) ===

    @Test
    void decode_push_decodedAsArray() throws IncompleteFrameError {
        byte[] data = ">2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(2, array.getElements().size());
    }

    // === Incomplete frame tests ===

    @Test
    void decode_truncated_simpleString() {
        byte[] data = "+OK".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_bulkString_noData() {
        byte[] data = "$5\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_bulkString_partialData() {
        byte[] data = "$5\r\nhel".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_integer() {
        byte[] data = ":42".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_array() {
        byte[] data = "*2\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_largeArrayCount_doesNotPreallocateFromWireValue() {
        byte[] data = "*2000000000\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_emptyData() {
        byte[] data = new byte[0];
        assertThrows(IncompleteFrameError.class, () -> RespCodec.decode(data));
    }

    // === Garbage / protocol error tests ===

    @Test
    void decode_garbage_unknownPrefix() {
        byte[] data = "X garbage\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_invalidInteger() {
        byte[] data = ":not_a_number\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_invalidBulkStringLength() {
        byte[] data = "$abc\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_bulkStringLengthBelowNullSentinel() {
        byte[] data = "$-2\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_arrayLengthBelowNullSentinel() {
        byte[] data = "*-2\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_bulkStringWithoutTrailingCrlf() {
        byte[] data = "$5\r\nhelloxx".getBytes(StandardCharsets.UTF_8);
        assertThrows(PhotonProtocolError.class, () -> RespCodec.decode(data));
    }

    // === Round trip tests ===

    @Test
    void roundTrip_command() throws IncompleteFrameError {
        byte[] encoded = RespCodec.encodeCommand("photon.request",
                "GET".getBytes(StandardCharsets.UTF_8),
                "https://example.com".getBytes(StandardCharsets.UTF_8));
        DecodeResult result = RespCodec.decode(encoded);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(3, array.getElements().size());
        assertArrayEquals("photon.request".getBytes(),
                ((RespBulkString) array.getElements().get(0)).getValue());
        assertArrayEquals("GET".getBytes(),
                ((RespBulkString) array.getElements().get(1)).getValue());
        assertArrayEquals("https://example.com".getBytes(),
                ((RespBulkString) array.getElements().get(2)).getValue());
        assertEquals(encoded.length, result.getPosition());
    }

    @Test
    void roundTrip_binaryData() throws IncompleteFrameError {
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        byte[] encoded = RespCodec.encodeCommand("CMD", binaryData);
        DecodeResult result = RespCodec.decode(encoded);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertArrayEquals(binaryData, ((RespBulkString) array.getElements().get(1)).getValue());
    }
}
