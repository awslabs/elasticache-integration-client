package software.amazon.elasticache.integrations.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ElastiCacheIntegrationsErrorHierarchyTest {

    @Test
    void authErrorIsNotAConnectionError() {
        assertEquals(
                ElastiCacheIntegrationsError.class, ElastiCacheIntegrationsAuthError.class.getSuperclass());
        assertFalse(
                ElastiCacheIntegrationsConnectionError.class.isAssignableFrom(
                        ElastiCacheIntegrationsAuthError.class));
    }
}
