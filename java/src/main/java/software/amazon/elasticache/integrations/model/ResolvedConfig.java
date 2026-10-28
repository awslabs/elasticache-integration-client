package software.amazon.elasticache.integrations.model;

import java.util.Map;
import java.util.Objects;

/** Resolved configuration returned by the {@code aws.config} handshake. */
public final class ResolvedConfig {

    private final String version;
    private final Integer refreshInterval;
    private final Map<String, Object> raw;

    /**
     * Create a resolved config.
     *
     * @param version the config version
     * @param refreshInterval the config refresh interval in seconds
     * @param raw the parsed config document
     */
    public ResolvedConfig(String version, Integer refreshInterval, Map<String, Object> raw) {
        this.version = version;
        this.refreshInterval = refreshInterval;
        this.raw = raw;
    }

    /**
     * Return the config version.
     *
     * @return the config version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Return the refresh interval.
     *
     * @return the config refresh interval in seconds
     */
    public Integer getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * Return the parsed config document.
     *
     * @return the parsed config document
     */
    public Map<String, Object> getRaw() {
        return raw;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResolvedConfig)) return false;
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
