package software.amazon.elasticache.integrations.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;

class RespCodecTest {

    // === Encode tests ===

    @Test
    void encodeCommand_simpleCommand() {
        byte[] result = RespCodec.encodeCommand("TEST");
        String expected = "*1\r\n$4\r\nTEST\r\n";
        assertEquals(expected, new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void encodeCommand_withArgs() {
        byte[] result =
                RespCodec.encodeCommand(
                        "SET",
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
        assertThrows(
                IllegalArgumentException.class, () -> RespCodec.encodeCommand("CMD", (byte[]) null));
    }

    // === Decode tests - Simple String ===

    @Test
    void decode_simpleString() throws IncompleteFrame {
        byte[] data = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespSimpleString);
        assertArrayEquals(
                "OK".getBytes(StandardCharsets.UTF_8), ((RespSimpleString) result.getValue()).getValue());
        assertEquals(data.length, result.getPosition());
    }

    @Test
    void decode_simpleString_empty() throws IncompleteFrame {
        byte[] data = "+\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespSimpleString);
        assertArrayEquals(new byte[0], ((RespSimpleString) result.getValue()).getValue());
    }

    // === Decode tests - Integer ===

    @Test
    void decode_integer() throws IncompleteFrame {
        byte[] data = ":42\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(42, ((RespInteger) result.getValue()).getValue());
    }

    @Test
    void decode_negativeInteger() throws IncompleteFrame {
        byte[] data = ":-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(-1, ((RespInteger) result.getValue()).getValue());
    }

    @Test
    void decode_zero() throws IncompleteFrame {
        byte[] data = ":0\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespInteger);
        assertEquals(0, ((RespInteger) result.getValue()).getValue());
    }

    // === Decode tests - Error ===

    @Test
    void decode_error() throws IncompleteFrame {
        byte[] data = "-ERR something went wrong\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespError);
        RespError error = (RespError) result.getValue();
        assertEquals("ERR", error.getCode());
        assertEquals("something went wrong", error.getMessage());
    }

    @Test
    void decode_error_noMessage() throws IncompleteFrame {
        byte[] data = "-ERR\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespError);
        RespError error = (RespError) result.getValue();
        assertEquals("ERR", error.getCode());
        assertEquals("", error.getMessage());
    }

    // === Decode tests - Bulk String ===

    @Test
    void decode_bulkString() throws IncompleteFrame {
        byte[] data = "$5\r\nhello\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespBulkString);
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8), ((RespBulkString) result.getValue()).getValue());
    }

    @Test
    void decode_bulkString_empty() throws IncompleteFrame {
        byte[] data = "$0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespBulkString);
        assertArrayEquals(new byte[0], ((RespBulkString) result.getValue()).getValue());
    }

    @Test
    void decode_nullBulkString() throws IncompleteFrame {
        byte[] data = "$-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespNull);
    }

    // === Decode tests - RESP3 null ===

    @Test
    void decode_resp3Null() throws IncompleteFrame {
        byte[] data = "_\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertSame(RespNull.INSTANCE, result.getValue());
        assertEquals(3, result.getPosition());
    }

    @Test
    void decode_resp3Null_incompleteWithoutCrlf() {
        byte[] data = "_".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_resp3Null_malformedNonEmptyHeaderRejected() {
        byte[] data = "_garbage\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_resp3Null_insideArray() throws IncompleteFrame {
        byte[] data = "*2\r\n_\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        RespArray array = (RespArray) result.getValue();
        assertSame(RespNull.INSTANCE, array.getElements().get(0));
        assertArrayEquals(
                "foo".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(1)).getValue());
    }

    @Test
    void decode_resp3Null_beforeAnotherFrame() throws IncompleteFrame {
        byte[] data = "_\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult first = RespCodec.decode(data, 0);
        assertSame(RespNull.INSTANCE, first.getValue());
        DecodeResult second = RespCodec.decode(data, first.getPosition());
        assertArrayEquals(
                "foo".getBytes(StandardCharsets.UTF_8), ((RespBulkString) second.getValue()).getValue());
    }

    // === Decode tests - Array ===

    @Test
    void decode_array() throws IncompleteFrame {
        byte[] data = "*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(2, array.getElements().size());
        assertArrayEquals(
                "foo".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(0)).getValue());
        assertArrayEquals(
                "bar".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(1)).getValue());
    }

    @Test
    void decode_emptyArray() throws IncompleteFrame {
        byte[] data = "*0\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(0, array.getElements().size());
    }

    @Test
    void decode_nullArray() throws IncompleteFrame {
        byte[] data = "*-1\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespNull);
    }

    @Test
    void decode_nestedArray() throws IncompleteFrame {
        byte[] data = "*2\r\n*1\r\n:1\r\n*1\r\n:2\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray outer = (RespArray) result.getValue();
        assertEquals(2, outer.getElements().size());
        assertTrue(outer.getElements().get(0) instanceof RespArray);
        assertTrue(outer.getElements().get(1) instanceof RespArray);
    }

    @Test
    void decode_mixedArray() throws IncompleteFrame {
        byte[] data = "*3\r\n:1\r\n$5\r\nhello\r\n+OK\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(3, array.getElements().size());
        assertTrue(array.getElements().get(0) instanceof RespInteger);
        assertTrue(array.getElements().get(1) instanceof RespBulkString);
        assertTrue(array.getElements().get(2) instanceof RespSimpleString);
    }

    // === Decode tests - Map ===

    @Test
    void decode_map() throws IncompleteFrame {
        byte[] data =
                ("%2\r\n$5\r\nproto\r\n:3\r\n" + "$6\r\nserver\r\n$6\r\nvalkey\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);

        Map<RespValue, RespValue> expected = new LinkedHashMap<>();
        expected.put(new RespBulkString("proto".getBytes(StandardCharsets.UTF_8)), new RespInteger(3));
        expected.put(
                new RespBulkString("server".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("valkey".getBytes(StandardCharsets.UTF_8)));
        assertEquals(new RespMap(expected), result.getValue());
        assertEquals(data.length, result.getPosition());
    }

    @Test
    void decode_emptyMap() throws IncompleteFrame {
        DecodeResult result = RespCodec.decode("%0\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(new RespMap(Collections.emptyMap()), result.getValue());
    }

    @Test
    void decode_negativeMapLength_throwsProtocolError() {
        byte[] data = "%-1\r\n".getBytes(StandardCharsets.UTF_8);

        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncatedMapValue_throwsIncompleteFrame() {
        byte[] data = "%1\r\n$5\r\nproto\r\n".getBytes(StandardCharsets.UTF_8);

        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    // === Decode tests - Push (>) ===

    @Test
    void decode_push_decodedAsDistinctPushValue() throws IncompleteFrame {
        byte[] data = ">2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n".getBytes(StandardCharsets.UTF_8);
        DecodeResult result = RespCodec.decode(data);
        assertTrue(result.getValue() instanceof RespPush);
        RespPush push = (RespPush) result.getValue();
        assertEquals(2, push.getElements().size());
        assertFalse(result.getValue() instanceof RespArray);
    }

    @Test
    void decode_emptyPush() throws IncompleteFrame {
        DecodeResult result = RespCodec.decode(">0\r\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(new RespPush(Collections.emptyList()), result.getValue());
    }

    @Test
    void decode_nullPush_throwsProtocolError() {
        byte[] data = ">-1\r\n".getBytes(StandardCharsets.UTF_8);

        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    // === Incomplete frame tests ===

    @Test
    void decode_truncated_simpleString() {
        byte[] data = "+OK".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_bulkString_noData() {
        byte[] data = "$5\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_bulkString_partialData() {
        byte[] data = "$5\r\nhel".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_integer() {
        byte[] data = ":42".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_truncated_array() {
        byte[] data = "*2\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_largeArrayCount_doesNotPreallocateFromWireValue() {
        byte[] data = "*2000000000\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_largeMapCount_doesNotPreallocateFromWireValue() {
        byte[] data = "%2000000000\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_emptyData() {
        byte[] data = new byte[0];
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }

    // === Garbage / protocol error tests ===

    @Test
    void decode_garbage_unknownPrefix() {
        byte[] data = "X garbage\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_invalidInteger() {
        byte[] data = ":not_a_number\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_invalidBulkStringLength() {
        byte[] data = "$abc\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_bulkStringLengthBelowNullSentinel() {
        byte[] data = "$-2\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_arrayLengthBelowNullSentinel() {
        byte[] data = "*-2\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    @Test
    void decode_garbage_bulkStringWithoutTrailingCrlf() {
        byte[] data = "$5\r\nhelloxx".getBytes(StandardCharsets.UTF_8);
        assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
    }

    // === Round trip tests ===

    @Test
    void roundTrip_command() throws IncompleteFrame {
        byte[] encoded =
                RespCodec.encodeCommand(
                        "aws.request",
                        "GET".getBytes(StandardCharsets.UTF_8),
                        "https://example.com".getBytes(StandardCharsets.UTF_8));
        DecodeResult result = RespCodec.decode(encoded);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertEquals(3, array.getElements().size());
        assertArrayEquals(
                "aws.request".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(0)).getValue());
        assertArrayEquals(
                "GET".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(1)).getValue());
        assertArrayEquals(
                "https://example.com".getBytes(StandardCharsets.UTF_8),
                ((RespBulkString) array.getElements().get(2)).getValue());
        assertEquals(encoded.length, result.getPosition());
    }

    @Test
    void roundTrip_binaryData() throws IncompleteFrame {
        byte[] binaryData = new byte[] {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        byte[] encoded = RespCodec.encodeCommand("CMD", binaryData);
        DecodeResult result = RespCodec.decode(encoded);
        assertTrue(result.getValue() instanceof RespArray);
        RespArray array = (RespArray) result.getValue();
        assertArrayEquals(binaryData, ((RespBulkString) array.getElements().get(1)).getValue());
    }

    // === Bounded decode (reader buffer with unwritten capacity) ===

    @Test
    void decode_withEnd_ignoresBytesPastEnd() throws IncompleteFrame {
        byte[] data = "$5\r\nhello\r\n+OK\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data, 0, 9));
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data, 11, 14));
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data, 11, 11));

        DecodeResult bulk = RespCodec.decode(data, 0, 11);
        assertArrayEquals(
                "hello".getBytes(StandardCharsets.UTF_8), ((RespBulkString) bulk.getValue()).getValue());
        assertEquals(11, bulk.getPosition());
        DecodeResult ok = RespCodec.decode(data, 11, data.length);
        assertArrayEquals(
                "OK".getBytes(StandardCharsets.UTF_8), ((RespSimpleString) ok.getValue()).getValue());
    }

    @Test
    void decode_withEnd_boundsNestedElements() {
        byte[] data = "*2\r\n:1\r\n:2\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data, 0, data.length - 1));
    }

    @Test
    void decode_withEnd_rejectsOutOfRangeBounds() {
        byte[] data = "+OK\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IndexOutOfBoundsException.class, () -> RespCodec.decode(data, -1, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> RespCodec.decode(data, 3, 2));
        assertThrows(IndexOutOfBoundsException.class, () -> RespCodec.decode(data, 0, 6));
    }

    // === Nesting depth cap ===

    /** Wraps a single {@code :1} integer in {@code levels} aggregates of the given prefix. */
    private static byte[] nestedFrame(char prefix, int levels) {
        String unit;
        switch (prefix) {
            case '*':
                unit = "*1\r\n";
                break;
            case '%':
                unit = "%1\r\n:0\r\n";
                break;
            default:
                unit = ">2\r\n+kind\r\n";
                break;
        }
        StringBuilder frame = new StringBuilder();
        for (int i = 0; i < levels; i++) {
            frame.append(unit);
        }
        frame.append(":1\r\n");
        return frame.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static RespValue unwrap(RespValue value) {
        if (value instanceof RespArray) {
            return ((RespArray) value).getElements().get(0);
        }
        if (value instanceof RespPush) {
            return ((RespPush) value).getElements().get(1);
        }
        return ((RespMap) value).getEntries().values().iterator().next();
    }

    @ParameterizedTest
    @ValueSource(chars = {'*', '%', '>'})
    void decodeNestingPastTheCapRaisesProtocolError(char prefix) {
        byte[] data = nestedFrame(prefix, 101);
        ElastiCacheIntegrationsProtocolError e =
                assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
        assertEquals("RESP nesting exceeds 100 levels", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(chars = {'*', '%', '>'})
    void decodeNestingAtTheCapDecodes(char prefix) throws IncompleteFrame {
        byte[] data = nestedFrame(prefix, 100);
        DecodeResult result = RespCodec.decode(data);
        RespValue value = result.getValue();
        for (int i = 0; i < 100; i++) {
            value = unwrap(value);
        }
        assertEquals(1L, ((RespInteger) value).getValue());
        assertEquals(data.length, result.getPosition());
    }

    @Test
    void decodeNestingPastTheCapRaisesFarPastTheStackLimit() {
        byte[] data = nestedFrame('*', 5000);
        ElastiCacheIntegrationsProtocolError e =
                assertThrows(ElastiCacheIntegrationsProtocolError.class, () -> RespCodec.decode(data));
        assertEquals("RESP nesting exceeds 100 levels", e.getMessage());
    }

    @Test
    void decodeTruncatedNestingUnderTheCapRaisesIncompleteFrame() {
        StringBuilder frame = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            frame.append("*1\r\n");
        }
        byte[] data = frame.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(IncompleteFrame.class, () -> RespCodec.decode(data));
    }
}
