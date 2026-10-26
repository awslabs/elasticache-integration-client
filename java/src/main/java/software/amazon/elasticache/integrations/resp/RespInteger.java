package software.amazon.elasticache.integrations.resp;

import java.util.Objects;

/** RESP3 integer value. */
public class RespInteger implements RespValue {

    private final long value;

    public RespInteger(long value) {
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespInteger that = (RespInteger) o;
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return "RespInteger{" + value + "}";
    }
}
