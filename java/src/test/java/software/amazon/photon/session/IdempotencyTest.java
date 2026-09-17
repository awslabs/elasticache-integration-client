package software.amazon.photon.session;

import org.junit.jupiter.api.Test;
import software.amazon.photon.model.RequestMetadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyTest {

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
