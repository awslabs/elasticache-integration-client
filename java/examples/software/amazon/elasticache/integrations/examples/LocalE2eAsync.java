package software.amazon.elasticache.integrations.examples;

import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Runs the ElastiCache Integrations DynamoDB E2E scenarios with the asynchronous client.
 *
 * <p>The scenarios match {@link LocalE2e}. Concurrent reads are issued as
 * in-flight futures rather than on caller threads.
 */
public final class LocalE2eAsync {

    private LocalE2eAsync() {}

    public static void main(String[] args) throws Exception {
        E2eSupport.requireTunnelResolution();
        try (E2eSupport e2e = new E2eSupport();
             DynamoDbAsyncClient integrations = e2e.elastiCacheIntegrationsBuilder()
                     .installAsync(DynamoDbAsyncClient.builder()
                             .region(e2e.region)
                             .credentialsProvider(e2e.credentials))) {
            cacheHit(e2e, integrations);
            concurrentReads(e2e, integrations);
            query(e2e, integrations);
            writePassThrough(e2e, integrations);
            originError(e2e, integrations);
            largeBinaryItem(e2e, integrations);
            batchReadsAndScan(e2e, integrations);
            deletePassThrough(e2e, integrations);
            System.out.println("SUCCESS: all ElastiCache Integrations async E2E scenarios passed.");
        }
    }

    private static void cacheHit(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== Cache hit after origin delete");
        String itemId = e2e.runId + "-hit";
        e2e.seed(itemId, false);
        GetItemRequest request = e2e.getRequest(e2e.tableName, itemId);

        E2eSupport.assertItem(timedGet(integrations, request, "GetItem #1").item(), itemId);

        e2e.deleteFromOrigin(itemId, false);

        GetItemResponse second = timedGet(
                integrations,
                request,
                "GetItem #2 (after DynamoDB delete)"
        );
        E2eSupport.assertItem(second.item(), itemId);
        System.out.println(
                "PASS: ElastiCache Integrations returned the cached item after it was deleted from DynamoDB."
        );
    }

    private static void concurrentReads(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== " + e2e.concurrency + " concurrent reads on distinct keys");
        List<String> itemIds = new ArrayList<>();
        for (int index = 0; index < e2e.concurrency; index++) {
            String itemId = e2e.runId + "-concurrent-" + index;
            e2e.seed(itemId, true);
            itemIds.add(itemId);
        }

        readAll(e2e, integrations, itemIds, "Cold reads");
        for (String itemId : itemIds) {
            e2e.deleteFromOrigin(itemId, true);
        }
        readAll(e2e, integrations, itemIds, "Reads after DynamoDB delete");
        System.out.println(
                "PASS: concurrent responses matched their requests and were served "
                        + "from the cache."
        );
    }

    private static void readAll(
            E2eSupport e2e,
            DynamoDbAsyncClient integrations,
            List<String> itemIds,
            String label) {
        long started = System.nanoTime();
        List<CompletableFuture<GetItemResponse>> responses = new ArrayList<>();
        for (String itemId : itemIds) {
            responses.add(integrations.getItem(e2e.getRequest(e2e.tableName, itemId)));
        }
        CompletableFuture.allOf(responses.toArray(new CompletableFuture<?>[0])).join();
        for (int index = 0; index < itemIds.size(); index++) {
            // A cross-completed response would carry another key's item.
            E2eSupport.assertItem(responses.get(index).join().item(), itemIds.get(index));
        }
        System.out.printf(
                "%s: %d GetItems in %.1f ms, each matched its key%n",
                label,
                itemIds.size(),
                (System.nanoTime() - started) / 1_000_000.0
        );
    }

    private static void query(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== Query through ElastiCache Integrations");
        String itemId = e2e.runId + "-query";
        e2e.seed(itemId, false);
        QueryResponse response = integrations.query(e2e.queryRequest(itemId)).join();
        if (response.items().size() != 1) {
            throw new IllegalStateException("Unexpected Query response: " + response.items());
        }
        E2eSupport.assertItem(response.items().get(0), itemId);
        System.out.println("PASS: Query returned the seeded item.");
    }

    private static void writePassThrough(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== PutItem through ElastiCache Integrations");
        String itemId = e2e.runId + "-write";
        Map<String, AttributeValue> item = E2eSupport.item(itemId);
        e2e.track(itemId);
        integrations.putItem(PutItemRequest.builder()
                .tableName(e2e.tableName)
                .item(item)
                .build()).join();
        E2eSupport.assertItem(e2e.consistentGet(itemId), itemId);
        System.out.println(
                "PASS: a consistent DynamoDB read returned the item written through ElastiCache Integrations."
        );
    }

    private static void originError(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== Origin error on a missing table");
        try {
            integrations.getItem(e2e.getRequest(e2e.missingTable(), "missing")).join();
        } catch (CompletionException error) {
            if (!(error.getCause() instanceof ResourceNotFoundException)) {
                throw new IllegalStateException(
                        "Expected ResourceNotFoundException, got " + error.getCause(),
                        error
                );
            }
            System.out.println(
                    "PASS: GetItem on a missing table failed with ResourceNotFoundException."
            );
            return;
        }
        throw new IllegalStateException("GetItem on " + e2e.missingTable() + " succeeded");
    }

    private static void largeBinaryItem(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== Large binary item");
        String itemId = e2e.runId + "-large";
        Map<String, AttributeValue> item = E2eSupport.largeItem(itemId);
        System.out.println("Seeding " + e2e.tableName + "/" + itemId + " ("
                + item.get("payload").b().asByteArrayUnsafe().length
                + " byte payload) in DynamoDB...");
        e2e.seed(item, true);
        GetItemRequest request = e2e.getRequest(e2e.tableName, itemId);

        E2eSupport.assertLargeItem(timedGet(integrations, request, "GetItem #1").item(), item);
        e2e.deleteFromOrigin(itemId, false);
        GetItemResponse second = timedGet(
                integrations,
                request,
                "GetItem #2 (after DynamoDB delete)"
        );
        E2eSupport.assertLargeItem(second.item(), item);
        System.out.println(
                "PASS: the large binary item matched byte for byte on the miss and the hit."
        );
    }

    private static void batchReadsAndScan(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== BatchGetItem and Scan through ElastiCache Integrations");
        String prefix = e2e.runId + "-batch-";
        List<String> itemIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            String itemId = prefix + index;
            e2e.seed(itemId, true);
            itemIds.add(itemId);
        }

        E2eSupport.assertBatchItems(
                integrations.batchGetItem(e2e.batchGetRequest(itemIds)).join(),
                e2e.tableName,
                itemIds
        );
        E2eSupport.assertItems(integrations.scan(e2e.scanRequest(prefix)).join().items(), itemIds);
        System.out.println("PASS: BatchGetItem and Scan returned every seeded item.");
    }

    private static void deletePassThrough(E2eSupport e2e, DynamoDbAsyncClient integrations) {
        System.out.println("== DeleteItem and BatchWriteItem through ElastiCache Integrations");
        String deletedId = e2e.runId + "-delete";
        e2e.seed(deletedId, false);
        integrations.deleteItem(e2e.deleteRequest(deletedId)).join();
        e2e.assertAbsent(deletedId);

        String batchDeletedId = e2e.runId + "-batch-delete";
        String batchPutId = e2e.runId + "-batch-put";
        e2e.seed(batchDeletedId, false);
        e2e.track(batchPutId);
        integrations.batchWriteItem(e2e.batchWriteRequest(batchPutId, batchDeletedId)).join();
        E2eSupport.assertItem(e2e.consistentGet(batchPutId), batchPutId);
        e2e.assertAbsent(batchDeletedId);
        System.out.println(
                "PASS: consistent DynamoDB reads reflected the deletes and the put written "
                        + "through ElastiCache Integrations."
        );
    }

    private static GetItemResponse timedGet(
            DynamoDbAsyncClient dynamodb,
            GetItemRequest request,
            String label) {
        long started = System.nanoTime();
        GetItemResponse response = dynamodb.getItem(request).join();
        E2eSupport.printTimedGet(label, response, started);
        return response;
    }
}
