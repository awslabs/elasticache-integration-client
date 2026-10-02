package software.amazon.photon.resp;

import java.util.Arrays;

/** RESP3 simple string value (returned as bytes). */
public class RespSimpleString implements RespValue {

    private final byte[] value;

    public RespSimpleString(byte[] value) {
        this.value = value;
    }

    public byte[] getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespSimpleString that = (RespSimpleString) o;
        return Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return "RespSimpleString{" + new String(value) + "}";
    }
}
