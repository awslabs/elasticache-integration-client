"""Replay-safe operations loaded from the shared language-neutral catalog."""

import json
from pathlib import Path


def _catalog_path() -> Path:
    repository_catalog = (
        Path(__file__).resolve().parents[2] / "shared" / "idempotent-operations.json"
    )
    if repository_catalog.is_file():
        return repository_catalog
    return Path(__file__).with_name("idempotent-operations.json")


with _catalog_path().open() as catalog_file:
    IDEMPOTENT_OPERATIONS = {
        service: frozenset(operations) for service, operations in json.load(catalog_file).items()
    }


def is_idempotent_operation(service: str, operation: str) -> bool:
    """Return whether retrying the operation after an unknown outcome is replay-safe."""
    return operation in IDEMPOTENT_OPERATIONS.get(service.lower(), ())
