package software.amazon.elasticache.integrations.resp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;

/** RESP3 wire protocol encoder and decoder. */
public final class RespCodec {

    private static final byte[] CRLF = new byte[] {'\r', '\n'};

    /** Deepest aggregate nesting the decoder accepts. The top-level value is level 1. */
    private static final int MAX_NESTING_DEPTH = 100;

    private RespCodec() {}

    /**
     * Encode a command with arguments as a RESP3 array of bulk strings. All args must be byte arrays.
     *
     * @param command the command name
     * @param args the arguments as byte arrays
     * @return encoded RESP3 bytes
     */
    public static byte[] encodeCommand(String command, byte[]... args) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        for (byte[] arg : args) {
            if (arg == null) {
                throw new IllegalArgumentException("args must not contain null");
            }
        }

        int totalElements = 1 + args.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // *N\r\n
        out.write('*');
        writeAscii(out, Integer.toString(totalElements));
        writeCrlf(out);

        // command as bulk string
        byte[] cmdBytes = command.getBytes(StandardCharsets.UTF_8);
        writeBulkString(out, cmdBytes);

        // each arg as bulk string
        for (byte[] arg : args) {
            writeBulkString(out, arg);
        }

        return out.toByteArray();
    }

    private static void writeBulkString(ByteArrayOutputStream out, byte[] data) {
        out.write('$');
        writeAscii(out, Integer.toString(data.length));
        writeCrlf(out);
        out.write(data, 0, data.length);
        writeCrlf(out);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static void writeCrlf(ByteArrayOutputStream out) {
        out.write(CRLF, 0, CRLF.length);
    }

    /**
     * Decode one RESP3 value from the given byte array starting at the given position.
     *
     * @param data the byte array
     * @param start the start position
     * @return a DecodeResult containing the decoded value and the position after it
     * @throws IncompleteFrame if data is truncated
     * @throws ElastiCacheIntegrationsProtocolError if data is malformed / garbage
     * @throws IndexOutOfBoundsException unless {@code 0 <= start <= data.length}
     */
    public static DecodeResult decode(byte[] data, int start) throws IncompleteFrame {
        return decode(data, start, data.length);
    }

    /**
     * Decode one RESP3 value from {@code data[start, end)}, ignoring any bytes at or past {@code
     * end}. A reader passes the written length of a buffer that has spare capacity.
     *
     * @param data the byte array
     * @param start the start position
     * @param end the position after the last readable byte
     * @return a DecodeResult containing the decoded value and the position after it
     * @throws IncompleteFrame if the value does not end before {@code end}
     * @throws ElastiCacheIntegrationsProtocolError if data is malformed / garbage
     * @throws IndexOutOfBoundsException unless {@code 0 <= start <= end <= data.length}
     */
    public static DecodeResult decode(byte[] data, int start, int end) throws IncompleteFrame {
        return decode(data, start, end, 1);
    }

    private static DecodeResult decode(byte[] data, int start, int end, int depth)
            throws IncompleteFrame {
        if (start < 0 || start > end || end > data.length) {
            throw new IndexOutOfBoundsException(
                    "Range [" + start + ", " + end + ") out of bounds for length " + data.length);
        }
        if (start == end) {
            throw new IncompleteFrame("No data available at position " + start);
        }

        byte prefix = data[start];
        switch (prefix) {
            case '+':
                return decodeSimpleString(data, start, end);
            case ':':
                return decodeInteger(data, start, end);
            case '-':
                return decodeError(data, start, end);
            case '$':
                return decodeBulkString(data, start, end);
            case '*':
                return decodeArray(data, start, end, depth);
            case '>':
                return decodePush(data, start, end, depth);
            case '%':
                return decodeMap(data, start, end, depth);
            case '_':
                return decodeNull(data, start, end);
            default:
                throw new ElastiCacheIntegrationsProtocolError(
                        "Unknown RESP prefix: "
                                + (char) prefix
                                + " (0x"
                                + Integer.toHexString(prefix & 0xFF)
                                + ")");
        }
    }

    /** Decode one RESP3 value from the given byte array starting at position 0. */
    public static DecodeResult decode(byte[] data) throws IncompleteFrame {
        return decode(data, 0);
    }

    private static DecodeResult decodeSimpleString(byte[] data, int start, int end)
            throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete simple string");
        }
        int len = crlfPos - (start + 1);
        byte[] value = new byte[len];
        System.arraycopy(data, start + 1, value, 0, len);
        return new DecodeResult(new RespSimpleString(value), crlfPos + 2);
    }

    private static DecodeResult decodeInteger(byte[] data, int start, int end)
            throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete integer");
        }
        int len = crlfPos - (start + 1);
        String numStr = new String(data, start + 1, len, StandardCharsets.US_ASCII);
        try {
            long value = Long.parseLong(numStr);
            return new DecodeResult(new RespInteger(value), crlfPos + 2);
        } catch (NumberFormatException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid integer: " + numStr);
        }
    }

    private static DecodeResult decodeError(byte[] data, int start, int end) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete error");
        }
        int len = crlfPos - (start + 1);
        String line = new String(data, start + 1, len, StandardCharsets.UTF_8);
        int spaceIdx = line.indexOf(' ');
        String code;
        String message;
        if (spaceIdx >= 0) {
            code = line.substring(0, spaceIdx);
            message = line.substring(spaceIdx + 1);
        } else {
            code = line;
            message = "";
        }
        return new DecodeResult(new RespError(code, message), crlfPos + 2);
    }

    private static DecodeResult decodeBulkString(byte[] data, int start, int end)
            throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete bulk string length");
        }
        String lenStr = new String(data, start + 1, crlfPos - (start + 1), StandardCharsets.US_ASCII);
        int length;
        try {
            length = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid bulk string length: " + lenStr);
        }

        // $-1 = null bulk string
        if (length == -1) {
            return new DecodeResult(RespNull.INSTANCE, crlfPos + 2);
        }
        if (length < -1) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid bulk string length: " + length);
        }

        int dataStart = crlfPos + 2;
        long dataEndLong = (long) dataStart + length;
        // Need data + CRLF
        if (dataEndLong + 2 > end) {
            throw new IncompleteFrame("Incomplete bulk string data");
        }
        int dataEnd = (int) dataEndLong;
        if (data[dataEnd] != '\r' || data[dataEnd + 1] != '\n') {
            throw new ElastiCacheIntegrationsProtocolError("Bulk string is not terminated by CRLF");
        }

        byte[] value = new byte[length];
        System.arraycopy(data, dataStart, value, 0, length);
        return new DecodeResult(new RespBulkString(value), dataEnd + 2);
    }

    /**
     * Decode a RESP3 null ({@code _\r\n}). The header must be empty: there is no length or value,
     * just the CRLF terminator immediately after the prefix.
     */
    private static DecodeResult decodeNull(byte[] data, int start, int end) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete null");
        }
        if (crlfPos != start + 1) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Malformed RESP3 null: expected an empty header");
        }
        return new DecodeResult(RespNull.INSTANCE, crlfPos + 2);
    }

    private static DecodeResult decodeArray(byte[] data, int start, int end, int depth)
            throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete array length");
        }
        checkDepth(depth);
        String countStr = new String(data, start + 1, crlfPos - (start + 1), StandardCharsets.US_ASCII);
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid array count: " + countStr);
        }

        // *-1 = null array
        if (count == -1) {
            return new DecodeResult(RespNull.INSTANCE, crlfPos + 2);
        }
        if (count < -1) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid array count: " + count);
        }

        List<RespValue> elements = new ArrayList<>();
        int pos = crlfPos + 2;
        for (int i = 0; i < count; i++) {
            DecodeResult element = decode(data, pos, end, depth + 1);
            elements.add(element.getValue());
            pos = element.getPosition();
        }
        return new DecodeResult(new RespArray(elements), pos);
    }

    private static DecodeResult decodePush(byte[] data, int start, int end, int depth)
            throws IncompleteFrame {
        DecodeResult result = decodeArray(data, start, end, depth);
        if (!(result.getValue() instanceof RespArray)) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Invalid RESP3 push payload: expected a non-null element list");
        }
        RespArray array = (RespArray) result.getValue();
        return new DecodeResult(new RespPush(array.getElements()), result.getPosition());
    }

    private static DecodeResult decodeMap(byte[] data, int start, int end, int depth)
            throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1, end);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete map length");
        }
        checkDepth(depth);
        String countStr = new String(data, start + 1, crlfPos - (start + 1), StandardCharsets.US_ASCII);
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid map count: " + countStr);
        }
        if (count < 0) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid map count: " + count);
        }

        Map<RespValue, RespValue> entries = new LinkedHashMap<>();
        int pos = crlfPos + 2;
        for (int i = 0; i < count; i++) {
            DecodeResult key = decode(data, pos, end, depth + 1);
            DecodeResult value = decode(data, key.getPosition(), end, depth + 1);
            entries.put(key.getValue(), value.getValue());
            pos = value.getPosition();
        }
        return new DecodeResult(new RespMap(entries), pos);
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "RESP nesting exceeds " + MAX_NESTING_DEPTH + " levels");
        }
    }

    /** Find the position of CRLF in {@code data[from, end)}. Returns -1 if not found. */
    private static int findCrlf(byte[] data, int from, int end) {
        for (int i = from; i < end - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
