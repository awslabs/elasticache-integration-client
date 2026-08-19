package software.amazon.photon.resp;

/**
 * RESP3 null value (null bulk string or null array).
 */
public class RespNull implements RespValue {

    public static final RespNull INSTANCE = new RespNull();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o != null && getClass() == o.getClass();
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
