package software.amazon.elasticache.integrations.resp;

import java.util.Arrays;

/** RESP3 bulk string value (byte array). */
public class RespBulkString implements RespValue {

    private final byte[] value;

    public RespBulkString(byte[] value) {
        this.value = value;
    }

    public byte[] getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespBulkString that = (RespBulkString) o;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RespBulkString{" + new String(value) + "}";
    }
}
