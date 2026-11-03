package software.amazon.elasticache.integrations.resp;

/** RESP3 null value (null bulk string or null array). */
public final class RespNull implements RespValue {

    /** The shared null value. */
    public static final RespNull INSTANCE = new RespNull();

    private RespNull() {}

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof RespNull;
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public String toString() {
        return "RespNull{}";
    }
}
