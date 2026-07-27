package software.amazon.photon.resp;

import software.amazon.photon.exception.PhotonProtocolError;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RESP3 wire protocol encoder and decoder.
 */
public final class RespCodec {

    private static final byte[] CRLF = new byte[]{'\r', '\n'};

    private RespCodec() {
    }

    /**
     * Encode a command with arguments as a RESP3 array of bulk strings.
     * All args must be byte arrays.
     *
     * @param command the command name
     * @param args    the arguments as byte arrays
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
     * @param data  the byte array
     * @param start the start position
     * @return a DecodeResult containing the decoded value and the position after it
     * @throws IncompleteFrame    if data is truncated
     * @throws PhotonProtocolError if data is malformed / garbage
     */
    public static DecodeResult decode(byte[] data, int start) throws IncompleteFrame {
        if (start >= data.length) {
            throw new IncompleteFrame("No data available at position " + start);
        }

        byte prefix = data[start];
        switch (prefix) {
            case '+':
                return decodeSimpleString(data, start);
            case ':':
                return decodeInteger(data, start);
            case '-':
                return decodeError(data, start);
            case '$':
                return decodeBulkString(data, start);
            case '*':
                return decodeArray(data, start);
            case '>':
                // Push type decoded same as array
                return decodeArray(data, start);
            default:
                throw new PhotonProtocolError("Unknown RESP prefix: " + (char) prefix + " (0x" + Integer.toHexString(prefix & 0xFF) + ")");
        }
    }

    /**
     * Decode one RESP3 value from the given byte array starting at position 0.
     */
    public static DecodeResult decode(byte[] data) throws IncompleteFrame {
        return decode(data, 0);
    }

    private static DecodeResult decodeSimpleString(byte[] data, int start) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete simple string");
        }
        int len = crlfPos - (start + 1);
        byte[] value = new byte[len];
        System.arraycopy(data, start + 1, value, 0, len);
        return new DecodeResult(new RespSimpleString(value), crlfPos + 2);
    }

    private static DecodeResult decodeInteger(byte[] data, int start) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete integer");
        }
        int len = crlfPos - (start + 1);
        String numStr = new String(data, start + 1, len, StandardCharsets.US_ASCII);
        try {
            long value = Long.parseLong(numStr);
            return new DecodeResult(new RespInteger(value), crlfPos + 2);
        } catch (NumberFormatException e) {
            throw new PhotonProtocolError("Invalid integer: " + numStr);
        }
    }

    private static DecodeResult decodeError(byte[] data, int start) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1);
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

    private static DecodeResult decodeBulkString(byte[] data, int start) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete bulk string length");
        }
        String lenStr = new String(data, start + 1, crlfPos - (start + 1), StandardCharsets.US_ASCII);
        int length;
        try {
            length = Integer.parseInt(lenStr);
        } catch (NumberFormatException e) {
            throw new PhotonProtocolError("Invalid bulk string length: " + lenStr);
        }

        // $-1 = null bulk string
        if (length == -1) {
            return new DecodeResult(RespNull.INSTANCE, crlfPos + 2);
        }

        int dataStart = crlfPos + 2;
        int dataEnd = dataStart + length;
        // Need data + CRLF
        if (dataEnd + 2 > data.length) {
            throw new IncompleteFrame("Incomplete bulk string data");
        }

        byte[] value = new byte[length];
        System.arraycopy(data, dataStart, value, 0, length);
        return new DecodeResult(new RespBulkString(value), dataEnd + 2);
    }

    private static DecodeResult decodeArray(byte[] data, int start) throws IncompleteFrame {
        int crlfPos = findCrlf(data, start + 1);
        if (crlfPos < 0) {
            throw new IncompleteFrame("Incomplete array length");
        }
        String countStr = new String(data, start + 1, crlfPos - (start + 1), StandardCharsets.US_ASCII);
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException e) {
            throw new PhotonProtocolError("Invalid array count: " + countStr);
        }

        // *-1 = null array
        if (count == -1) {
            return new DecodeResult(RespNull.INSTANCE, crlfPos + 2);
        }

        List<RespValue> elements = new ArrayList<>(count);
        int pos = crlfPos + 2;
        for (int i = 0; i < count; i++) {
            DecodeResult element = decode(data, pos);
            elements.add(element.getValue());
            pos = element.getPosition();
        }
        return new DecodeResult(new RespArray(elements), pos);
    }

    /**
     * Find the position of CRLF starting from the given position.
     * Returns -1 if not found.
     */
    private static int findCrlf(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
