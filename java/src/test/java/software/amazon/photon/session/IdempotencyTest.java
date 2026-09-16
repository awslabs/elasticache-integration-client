package software.amazon.photon.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.photon.model.RequestMetadata;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest {

    @Test
    void packagedOperationsMatchSharedCatalog() throws Exception {
        Map<String, List<String>> shared = new ObjectMapper().readValue(
                Files.readAllBytes(Paths.get("../shared/idempotent-operations.json")),
                new TypeReference<Map<String, List<String>>>() {});

        assertEquals(new HashSet<>(shared.get("dynamodb")), Idempotency.dynamodbOperations());
    }

    @Test
    void catalogIsConservative() {
        assertTrue(Idempotency.isIdempotent(new RequestMetadata("DynamoDB", "GetItem")));
        assertFalse(Idempotency.isIdempotent(new RequestMetadata("dynamodb", "UpdateItem")));
        assertFalse(Idempotency.isIdempotent(
                new RequestMetadata("dynamodb", "ExecuteStatement")));
        assertFalse(Idempotency.isIdempotent(
                new RequestMetadata("dynamodb", "ExecuteTransaction")));
        assertFalse(Idempotency.isIdempotent(new RequestMetadata("unknown", "GetItem")));
    }
}
