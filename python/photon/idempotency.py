"""Replay-safe operations copied from ``shared/idempotent-operations.json``."""

IDEMPOTENT_OPERATIONS = {
    "dynamodb": frozenset({
        "BatchGetItem",
        "DescribeBackup",
        "DescribeContinuousBackups",
        "DescribeContributorInsights",
        "DescribeEndpoints",
        "DescribeExport",
        "DescribeGlobalTable",
        "DescribeGlobalTableSettings",
        "DescribeImport",
        "DescribeKinesisStreamingDestination",
        "DescribeLimits",
        "DescribeTable",
        "DescribeTableReplicaAutoScaling",
        "DescribeTimeToLive",
        "ExecuteTransaction",
        "GetItem",
        "GetResourcePolicy",
        "ListBackups",
        "ListContributorInsights",
        "ListExports",
        "ListGlobalTables",
        "ListImports",
        "ListTables",
        "ListTagsOfResource",
        "Query",
        "Scan",
        "TransactGetItems",
    }),
}


def is_idempotent_operation(service: str, operation: str) -> bool:
    """Return whether retrying the operation after an unknown outcome is replay-safe."""
    return operation in IDEMPOTENT_OPERATIONS.get(service.lower(), ())
