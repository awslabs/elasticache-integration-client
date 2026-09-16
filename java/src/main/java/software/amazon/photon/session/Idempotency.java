package software.amazon.photon.session;

import software.amazon.photon.model.RequestMetadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Replay-safe operations copied from shared/idempotent-operations.json.
 */
final class Idempotency {

    private static final Set<String> DYNAMODB_OPERATIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
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
                    "TransactGetItems")));

    private Idempotency() {
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
