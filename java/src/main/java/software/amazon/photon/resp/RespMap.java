package software.amazon.photon.resp;

import java.util.Map;
import java.util.Objects;

/** RESP3 map value. */
public class RespMap implements RespValue {

    private final Map<RespValue, RespValue> entries;

    public RespMap(Map<RespValue, RespValue> entries) {
        this.entries = entries;
    }

    public Map<RespValue, RespValue> getEntries() {
        return entries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespMap respMap = (RespMap) o;
        return Objects.equals(entries, respMap.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries);
    }

    @Override
    public String toString() {
        return "RespMap{" + entries + "}";
    }
}
