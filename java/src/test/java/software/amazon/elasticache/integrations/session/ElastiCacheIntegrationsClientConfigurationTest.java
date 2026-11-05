package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;

class ElastiCacheIntegrationsClientConfigurationTest {

    @Test
    void clientConfigurationHasTheDocumentedDefaults() {
        ElastiCacheIntegrationsClientConfiguration defaults =
                ElastiCacheIntegrationsClientConfiguration.defaults();

        assertEquals(5_000, defaults.getCacheRequestTimeoutMs());
        assertEquals(5_000, defaults.getConnectionTimeoutMs());
        assertEquals(-1, defaults.getFallbackDurationSeconds());
        assertNull(defaults.getCachePolicyOverrides());
        assertEquals(3_600, defaults.getCachePolicyTimeoutSeconds());
        assertNull(defaults.getIamTokenRefreshDelay());
    }

    @Test
    void clientConfigurationRejectsUnusableValues() {
        assertConfigError(
                "cacheRequestTimeoutMs must be a positive number of milliseconds, at most 2147483647",
                ElastiCacheIntegrationsClientConfiguration.builder().cacheRequestTimeoutMs(0));
        assertConfigError(
                "cacheRequestTimeoutMs must be a positive number of milliseconds, at most 2147483647",
                ElastiCacheIntegrationsClientConfiguration.builder().cacheRequestTimeoutMs(-1));
        assertConfigError(
                "cacheRequestTimeoutMs must be a positive number of milliseconds, at most 2147483647",
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cacheRequestTimeoutMs(Integer.MAX_VALUE + 1L));
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
        assertEquals(
                Integer.MAX_VALUE,
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cacheRequestTimeoutMs(Integer.MAX_VALUE)
                        .build()
                        .getCacheRequestTimeoutMs());
    }

    @Test
    void clientConfigurationRejectsUnusableConnectionTimeout() {
        for (long bad : new long[] {0, -1, Long.MIN_VALUE}) {
            assertConfigError(
                    "connectionTimeoutMs must be a positive number of milliseconds",
                    ElastiCacheIntegrationsClientConfiguration.builder().connectionTimeoutMs(bad));
        }

        assertEquals(
                1,
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .connectionTimeoutMs(1)
                        .build()
                        .getConnectionTimeoutMs());
    }

    @Test
    void clientConfigurationRejectsOverridesThatAreNotJsonSerializable() {
        Map<Object, Object> nonStringKey = new HashMap<>();
        nonStringKey.put(1, "x");
        Object[][] cases = {
            {Double.NaN, "cachePolicyOverrides.item_ttl must be a finite number, got NaN"},
            {
                Double.POSITIVE_INFINITY,
                "cachePolicyOverrides.item_ttl must be a finite number, got Infinity"
            },
            {Float.NaN, "cachePolicyOverrides.item_ttl must be a finite number, got NaN"},
            {
                new Object(),
                "cachePolicyOverrides.item_ttl must be JSON-serializable, got java.lang.Object"
            },
            {
                Arrays.asList("ok", new StringBuilder("x")),
                "cachePolicyOverrides.item_ttl[1] must be JSON-serializable, "
                        + "got java.lang.StringBuilder"
            },
            {nonStringKey, "cachePolicyOverrides.item_ttl must have string keys, got java.lang.Integer"},
            {
                new AtomicLong(60),
                "cachePolicyOverrides.item_ttl must be JSON-serializable, "
                        + "got java.util.concurrent.atomic.AtomicLong"
            },
        };
        for (Object[] testCase : cases) {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("item_ttl", testCase[0]);
            assertConfigError(
                    (String) testCase[1],
                    ElastiCacheIntegrationsClientConfiguration.builder().cachePolicyOverrides(overrides));
        }
    }

    @Test
    void clientConfigurationRejectsCyclicOverrides() {
        Map<String, Object> cyclicMap = new HashMap<>();
        cyclicMap.put("item_ttl", cyclicMap);
        assertConfigError(
                "cachePolicyOverrides.item_ttl must not contain itself",
                ElastiCacheIntegrationsClientConfiguration.builder().cachePolicyOverrides(cyclicMap));

        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        assertConfigError(
                "cachePolicyOverrides.cached_tables[0] must not contain itself",
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cachePolicyOverrides(Collections.singletonMap("cached_tables", cyclicList)));
    }

    @Test
    void clientConfigurationCopiesSetsAndArraysAsLists() {
        List<String> tables = Arrays.asList("Users", "Sessions");
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("cached_tables", new LinkedHashSet<>(tables));
        overrides.put("write_through_tables", new String[] {"Users", "Sessions"});
        // The same list in two places is not a cycle.
        overrides.put("table_groups", Arrays.asList(tables, tables));

        Map<String, Object> copied =
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cachePolicyOverrides(overrides)
                        .build()
                        .getCachePolicyOverrides();

        assertEquals(tables, copied.get("cached_tables"));
        assertEquals(tables, copied.get("write_through_tables"));
        assertEquals(Arrays.asList(tables, tables), copied.get("table_groups"));
    }

    @Test
    void clientConfigurationAcceptsJsonOverrides() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("item_ttl", 60);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("cached_tables", Arrays.asList("Users", "Sessions"));
        overrides.put("item_ttl", 500L);
        overrides.put("query_ttl", 1.5);
        overrides.put("scan_ttl", new BigDecimal("2"));
        overrides.put("enabled", true);
        overrides.put("item_write_behavior", null);
        overrides.put("table_overrides", Collections.singletonMap("Users", table));

        assertEquals(
                overrides,
                ElastiCacheIntegrationsClientConfiguration.builder()
                        .cachePolicyOverrides(overrides)
                        .build()
                        .getCachePolicyOverrides());
    }

    private static void assertConfigError(
            String message, ElastiCacheIntegrationsClientConfiguration.Builder builder) {
        ElastiCacheIntegrationsConfigError error =
                assertThrows(ElastiCacheIntegrationsConfigError.class, builder::build);
        assertEquals(message, error.getMessage());
    }
}
