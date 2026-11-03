package software.amazon.elasticache.integrations.resp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** RESP3 simple string value (returned as bytes). */
public final class RespSimpleString implements RespValue {

    private final byte[] value;

    /**
     * Create a simple string value.
     *
     * @param value the raw bytes
     */
    public RespSimpleString(byte[] value) {
        this.value = value;
    }

    /**
     * Return the raw bytes.
     *
     * @return the raw bytes
     */
    public byte[] getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespSimpleString)) return false;
        RespSimpleString that = (RespSimpleString) o;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RespSimpleString{" + new String(value, StandardCharsets.UTF_8) + "}";
    }
}
