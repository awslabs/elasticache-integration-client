package software.amazon.elasticache.integrations.resp;

import java.util.List;
import java.util.Objects;

/**
 * A RESP3 push frame ({@code >}): an out-of-band message, not the reply to any command.
 *
 * <p>The first element is the kind, such as {@code aws.response}. The rest are its items.
 */
public final class RespPush implements RespValue {

    private final List<RespValue> elements;

    /**
     * Create a push value.
     *
     * @param elements the push elements
     */
    public RespPush(List<RespValue> elements) {
        this.elements = elements;
    }

    /**
     * Return the push elements.
     *
     * @return the push elements
     */
    public List<RespValue> getElements() {
        return elements;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RespPush)) return false;
        RespPush respPush = (RespPush) o;
        return Objects.equals(elements, respPush.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(elements);
    }

    @Override
    public String toString() {
        return "RespPush{" + elements + "}";
    }
}
