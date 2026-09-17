/**
 * Generated from ../../shared/idempotent-operations.json.
 * Run `npm run generate:idempotency` after changing the shared catalog.
 */
export const IDEMPOTENT_OPERATIONS: Readonly<Record<string, ReadonlySet<string>>> = {
  dynamodb: new Set([
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
  ]),
};

export function isIdempotentOperation(service: string, operation: string): boolean {
  return IDEMPOTENT_OPERATIONS[service.toLowerCase()]?.has(operation) ?? false;
}
