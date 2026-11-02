import json
from pathlib import Path

from aws_elasticache_integration_client.idempotency import (
    IDEMPOTENT_OPERATIONS,
    is_idempotent_operation,
)


def test_packaged_operations_match_shared_catalog():
    shared_path = Path(__file__).parents[2] / "shared" / "idempotent-operations.json"
    shared = json.loads(shared_path.read_text())
    packaged = {
        service: sorted(operations) for service, operations in IDEMPOTENT_OPERATIONS.items()
    }
    assert packaged == shared


def test_catalog_is_conservative():
    assert is_idempotent_operation("DynamoDB", "GetItem")
    assert not is_idempotent_operation("dynamodb", "UpdateItem")
    assert not is_idempotent_operation("dynamodb", "ExecuteStatement")
    assert not is_idempotent_operation("dynamodb", "ExecuteTransaction")
    assert not is_idempotent_operation("unknown", "GetItem")
