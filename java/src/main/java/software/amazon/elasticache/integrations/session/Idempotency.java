package software.amazon.elasticache.integrations.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.elasticache.integrations.model.RequestMetadata;

/** Replay-safe operations loaded from the packaged shared catalog. */
final class Idempotency {

    private static final Set<String> DYNAMODB_OPERATIONS = loadDynamoDbOperations();

    private Idempotency() {}

    private static Set<String> loadDynamoDbOperations() {
        try (InputStream catalogStream =
                Idempotency.class.getResourceAsStream("/idempotent-operations.json")) {
            if (catalogStream == null) {
                throw new IllegalStateException("Missing idempotent-operations.json resource");
            }
            Map<String, List<String>> catalog =
                    new ObjectMapper()
                            .readValue(catalogStream, new TypeReference<Map<String, List<String>>>() {});
            return Collections.unmodifiableSet(new HashSet<>(catalog.get("dynamodb")));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static boolean isIdempotent(RequestMetadata metadata) {
        return metadata != null
                && metadata.getService() != null
                && "dynamodb".equalsIgnoreCase(metadata.getService())
                && DYNAMODB_OPERATIONS.contains(metadata.getOperation());
    }

    static Set<String> dynamodbOperations() {
        return DYNAMODB_OPERATIONS;
    }
}
