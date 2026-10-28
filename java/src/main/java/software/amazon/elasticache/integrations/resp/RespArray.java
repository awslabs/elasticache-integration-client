package software.amazon.elasticache.integrations.resp;

import java.util.List;
import java.util.Objects;

/** RESP3 array value. */
public final class RespArray implements RespValue {

    private final List<RespValue> elements;

    /**
     * Create an array value.
     *
     * @param elements the array elements
     */
    public RespArray(List<RespValue> elements) {
        this.elements = elements;
    }

    /**
     * Return the array elements.
     *
     * @return the array elements
     */
    public List<RespValue> getElements() {
        return elements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespArray)) return false;
        RespArray respArray = (RespArray) o;
        return Objects.equals(elements, respArray.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(elements);
    }

    @Override
    public String toString() {
        return "RespArray{" + elements + "}";
    }
}
