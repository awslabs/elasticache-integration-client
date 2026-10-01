package software.amazon.photon.model;

import java.util.Map;
import java.util.Objects;

/** Configuration resolved from the Photon server. */
public class ResolvedConfig {

    private final String version;
    private final Integer refreshInterval;
    private final Map<String, Object> raw;

    public ResolvedConfig(String version, Integer refreshInterval, Map<String, Object> raw) {
        this.version = version;
        this.refreshInterval = refreshInterval;
        this.raw = raw;
    }

    public String getVersion() {
        return version;
    }

    public Integer getRefreshInterval() {
        return refreshInterval;
    }

    public Map<String, Object> getRaw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResolvedConfig that = (ResolvedConfig) o;
        return Objects.equals(version, that.version)
                && Objects.equals(refreshInterval, that.refreshInterval)
                && Objects.equals(raw, that.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, refreshInterval, raw);
    }

    @Override
    public String toString() {
        return "ResolvedConfig{version='" + version + "', refreshInterval=" + refreshInterval + "}";
    }
}
