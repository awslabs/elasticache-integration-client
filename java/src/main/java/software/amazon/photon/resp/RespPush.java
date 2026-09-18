package software.amazon.photon.resp;

import java.util.List;
import java.util.Objects;

/**
 * RESP3 push value.
 */
public class RespPush implements RespValue {

    private final List<RespValue> elements;

    public RespPush(List<RespValue> elements) {
        this.elements = elements;
    }

    public List<RespValue> getElements() {
        return elements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespPush respPush = (RespPush) o;
        return Objects.equals(elements, respPush.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elements);
    }

    @Override
    public String toString() {
        return "RespPush{" + elements + "}";
    }
}
