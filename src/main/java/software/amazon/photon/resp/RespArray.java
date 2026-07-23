package software.amazon.photon.resp;

import java.util.List;
import java.util.Objects;

/**
 * RESP3 array value.
 */
public class RespArray implements RespValue {

    private final List<RespValue> elements;

    public RespArray(List<RespValue> elements) {
        this.elements = elements;
    }

    public List<RespValue> getElements() {
        return elements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespArray respArray = (RespArray) o;
        return Objects.equals(elements, respArray.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elements);
    }

    @Override
    public String toString() {
        return "RespArray{" + elements + "}";
    }
}
