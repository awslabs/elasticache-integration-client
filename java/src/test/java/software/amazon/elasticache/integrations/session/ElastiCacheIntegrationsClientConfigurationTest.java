package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;

class ElastiCacheIntegrationsClientConfigurationTest {

    @Test
    void clientConfigurationHasTheDocumentedDefaults() {
        ElastiCacheIntegrationsClientConfiguration defaults =
                ElastiCacheIntegrationsClientConfiguration.defaults();

        assertEquals(5_000, defaults.getCacheRequestTimeoutMs());
        assertEquals(-1, defaults.getFallbackDurationSeconds());
        assertNull(defaults.getCachePolicyOverrides());
        assertEquals(3_600, defaults.getCachePolicyTimeoutSeconds());
        assertNull(defaults.getIamTokenRefreshDelay());
    }

    @Test
    void clientConfigurationRejectsUnusableValues() {
        assertConfigError(
                "cacheRequestTimeoutMs must be a positive number of milliseconds",
                ElastiCacheIntegrationsClientConfiguration.builder().cacheRequestTimeoutMs(0));
        assertConfigError(
                "cacheRequestTimeoutMs must be a positive number of milliseconds",
                ElastiCacheIntegrationsClientConfiguration.builder().cacheRequestTimeoutMs(-1));
        assertConfigError(
                "cachePolicyTimeoutSeconds must be a positive whole number of seconds",
                ElastiCacheIntegrationsClientConfiguration.builder().cachePolicyTimeoutSeconds(0));
        assertConfigError(
                "cachePolicyTimeoutSeconds must be a positive whole number of seconds",
                ElastiCacheIntegrationsClientConfiguration.builder().cachePolicyTimeoutSeconds(-5));
        assertConfigError(
                "cachePolicyTimeoutSeconds must be a positive whole number of seconds",
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cachePolicyTimeoutSeconds(Long.MAX_VALUE));
        String refreshMessage =
                "iamTokenRefreshDelay must be a Duration greater than 0 and less than 880 seconds, "
                        + "or null to use the toolkit default";
        for (Duration bad :
                Arrays.asList(
                        Duration.ZERO,
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(880),
                        Duration.ofSeconds(881),
                        Duration.ofDays(1))) {
            assertConfigError(
                    refreshMessage,
                    ElastiCacheIntegrationsClientConfiguration.builder().iamTokenRefreshDelay(bad));
        }

        // Values at the edge of the allowed range are accepted, and fallback takes any value.
        assertEquals(
                Duration.ofSeconds(879),
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .iamTokenRefreshDelay(Duration.ofSeconds(879))
                        .build()
                        .getIamTokenRefreshDelay());
        assertEquals(
                0,
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .fallbackDurationSeconds(0)
                        .build()
                        .getFallbackDurationSeconds());
        assertEquals(
                1,
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cacheRequestTimeoutMs(1)
                        .build()
                        .getCacheRequestTimeoutMs());
    }

    private static void assertConfigError(
            String message, ElastiCacheIntegrationsClientConfiguration.Builder builder) {
        ElastiCacheIntegrationsConfigError error =
                assertThrows(ElastiCacheIntegrationsConfigError.class, builder::build);
        assertEquals(message, error.getMessage());
    }
}
