package software.amazon.elasticache.integrations.examples;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClient;
import software.amazon.elasticache.integrations.session.ElastiCacheIntegrationsClientConfiguration;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration, origin-side DynamoDB helpers, and assertions shared by the
 * sync and async E2E examples.
 */
final class E2eSupport implements AutoCloseable {

    final String cacheHost = env("ELASTICACHE_CACHE_HOST", "localhost");
    final int cachePort = positiveIntEnv("ELASTICACHE_CACHE_PORT", 6379);
    final String cacheName = env("ELASTICACHE_CACHE_NAME", "elasticache-integrations-e2e-cache");
    final String cacheUserId = env(
            "ELASTICACHE_CACHE_USER_ID",
            "default.iam-user"
    );
    final int requestTimeoutMs = (int) Math.round(positiveDoubleEnv("ELASTICACHE_REQUEST_TIMEOUT_SECONDS", 5.0) * 1000);
    final int concurrency = positiveIntEnv("ELASTICACHE_CONCURRENCY", 16);
    // Defaults to us-east-1 when AWS_REGION is unset.
    final Region region = Region.of(env("AWS_REGION", "us-east-1"));
    final String tableName = env("ELASTICACHE_TABLE_NAME", "ElastiCacheIntegrationsTestTable");
    // A fresh ID per run forces the first ElastiCache Integrations read to be a cache miss and
    // keeps concurrent runs from deleting each other's items.
    final String runId = env("ELASTICACHE_ITEM_ID", "elasticache-integrations-e2e-" + UUID.randomUUID());

    final AwsCredentialsProvider credentials = DefaultCredentialsProvider.create();

    private final DynamoDbClient origin = DynamoDbClient.builder()
            .region(region)
            .credentialsProvider(credentials)
            .build();
    private final List<String> seeded = Collections.synchronizedList(new ArrayList<>());

    ElastiCacheIntegrationsClient.Builder elastiCacheIntegrationsBuilder() {
        return ElastiCacheIntegrationsClient.builder()
                .endpoint(cacheHost + ":" + cachePort)
                .cacheName(cacheName)
                .userId(cacheUserId)
                .clientConfiguration(ElastiCacheIntegrationsClientConfiguration.builder()
                        .cacheRequestTimeoutMs(requestTimeoutMs)
                        .build());
    }

    /**
     * Fails fast when ELASTICACHE_TUNNEL_HOST is set but {@link TunnelResolverProvider}
     * is not active, for example on a JDK older than 18.
     */
    static void requireTunnelResolution() throws UnknownHostException {
        String cacheHost = env("ELASTICACHE_CACHE_HOST", "localhost");
        String tunnelHost = System.getenv("ELASTICACHE_TUNNEL_HOST");
        if (tunnelHost == null || tunnelHost.isEmpty()) {
            return;
        }
        InetAddress resolved = InetAddress.getByName(cacheHost);
        if (!resolved.equals(InetAddress.getByName(tunnelHost))) {
            throw new IllegalStateException(cacheHost + " resolved to " + resolved
                    + " instead of ELASTICACHE_TUNNEL_HOST " + tunnelHost
                    + "; run the example on JDK 18 or later");
        }
    }

    Map<String, AttributeValue> seed(String itemId, boolean quiet) {
        return seed(item(itemId), quiet);
    }

    Map<String, AttributeValue> seed(Map<String, AttributeValue> item, boolean quiet) {
        String itemId = item.get("id").s();
        if (!quiet) {
            System.out.println("Seeding " + tableName + "/" + itemId
                    + " directly in DynamoDB...");
        }
        origin.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build());
        seeded.add(itemId);
        return item;
    }

    /** Records an item written through ElastiCache Integrations so cleanup deletes it. */
    void track(String itemId) {
        seeded.add(itemId);
    }

    void deleteFromOrigin(String itemId, boolean quiet) {
        if (!quiet) {
            System.out.println("Deleting " + tableName + "/" + itemId
                    + " directly from DynamoDB...");
        }
        origin.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key(itemId))
                .build());
        assertAbsent(itemId);
        if (!quiet) {
            System.out.println("Confirmed the item no longer exists in DynamoDB.");
        }
    }

    void assertAbsent(String itemId) {
        Map<String, AttributeValue> item = consistentGet(itemId);
        if (!item.isEmpty()) {
            throw new IllegalStateException("DynamoDB still contains the item: " + item);
        }
    }

    Map<String, AttributeValue> consistentGet(String itemId) {
        return origin.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(itemId))
                .consistentRead(true)
                .build()).item();
    }

    /** Deletes every item this run wrote, then closes the origin client. */
    @Override
    public void close() {
        try {
            synchronized (seeded) {
                for (String itemId : seeded) {
                    origin.deleteItem(DeleteItemRequest.builder()
                            .tableName(tableName)
                            .key(key(itemId))
                            .build());
                }
            }
        } finally {
            origin.close();
        }
    }

    GetItemRequest getRequest(String table, String itemId) {
        return GetItemRequest.builder()
                .tableName(table)
                .key(key(itemId))
                .build();
    }

    QueryRequest queryRequest(String itemId) {
        return QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("id = :id")
                .expressionAttributeValues(Collections.singletonMap(
                        ":id",
                        AttributeValue.builder().s(itemId).build()
                ))
                .build();
    }

    BatchGetItemRequest batchGetRequest(List<String> itemIds) {
        List<Map<String, AttributeValue>> keys = new ArrayList<>();
        for (String itemId : itemIds) {
            keys.add(key(itemId));
        }
        return BatchGetItemRequest.builder()
                .requestItems(Collections.singletonMap(
                        tableName,
                        KeysAndAttributes.builder().keys(keys).build()
                ))
                .build();
    }

    ScanRequest scanRequest(String idPrefix) {
        return ScanRequest.builder()
                .tableName(tableName)
                .filterExpression("begins_with(id, :prefix)")
                .expressionAttributeValues(Collections.singletonMap(
                        ":prefix",
                        AttributeValue.builder().s(idPrefix).build()
                ))
                .build();
    }

    DeleteItemRequest deleteRequest(String itemId) {
        return DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key(itemId))
                .build();
    }

    BatchWriteItemRequest batchWriteRequest(String putItemId, String deleteItemId) {
        return BatchWriteItemRequest.builder()
                .requestItems(Collections.singletonMap(tableName, Arrays.asList(
                        WriteRequest.builder()
                                .putRequest(PutRequest.builder().item(item(putItemId)).build())
                                .build(),
                        WriteRequest.builder()
                                .deleteRequest(DeleteRequest.builder()
                                        .key(key(deleteItemId))
                                        .build())
                                .build()
                )))
                .build();
    }

    String missingTable() {
        return runId + "-missing-table";
    }

    static void printTimedGet(String label, GetItemResponse response, long startedNanos) {
        System.out.printf(
                "%s: HTTP %d in %.1f ms%n",
                label,
                response.sdkHttpResponse().statusCode(),
                (System.nanoTime() - startedNanos) / 1_000_000.0
        );
    }

    static Map<String, AttributeValue> item(String itemId) {
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("id", AttributeValue.builder().s(itemId).build());
        item.put("name", AttributeValue.builder().s("elasticache-integrations-widget").build());
        item.put(
                "description",
                AttributeValue.builder()
                        .s("e2e test item for ElastiCache Integrations caching")
                        .build()
        );
        return item;
    }

    static Map<String, AttributeValue> largeItem(String itemId) {
        // DynamoDB caps an item at 400 KB, so this stays just under the limit.
        byte[] payload = new byte[380_000];
        new SecureRandom().nextBytes(payload);
        Map<String, AttributeValue> item = new LinkedHashMap<>();
        item.put("id", AttributeValue.builder().s(itemId).build());
        item.put("payload", AttributeValue.builder().b(SdkBytes.fromByteArray(payload)).build());
        item.put("text", AttributeValue.builder()
                .s("caf\u00e9 \u2713 \u65e5\u672c\u8a9e \ud83d\ude80")
                .build());
        return item;
    }

    static Map<String, AttributeValue> key(String itemId) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put("id", AttributeValue.builder().s(itemId).build());
        return key;
    }

    static void assertItem(Map<String, AttributeValue> actual, String itemId) {
        requireString(actual, "id", itemId);
        requireString(actual, "name", "elasticache-integrations-widget");
        requireString(actual, "description", "e2e test item for ElastiCache Integrations caching");
    }

    /** AttributeValue equality compares binary attributes by content. */
    static void assertLargeItem(
            Map<String, AttributeValue> actual,
            Map<String, AttributeValue> expected) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Unexpected large item for " + expected.get("id").s()
            );
        }
    }

    static void assertBatchItems(BatchGetItemResponse response, String table,
                                 List<String> itemIds) {
        if (response.hasUnprocessedKeys() && !response.unprocessedKeys().isEmpty()) {
            throw new IllegalStateException(
                    "BatchGetItem left keys unprocessed: " + response.unprocessedKeys()
            );
        }
        assertItems(response.responses().get(table), itemIds);
    }

    static void assertItems(List<Map<String, AttributeValue>> actual, List<String> itemIds) {
        if (actual == null || actual.size() != itemIds.size()) {
            throw new IllegalStateException("Unexpected items: " + actual);
        }
        Map<String, Map<String, AttributeValue>> byId = new LinkedHashMap<>();
        for (Map<String, AttributeValue> item : actual) {
            byId.put(item.get("id").s(), item);
        }
        for (String itemId : itemIds) {
            Map<String, AttributeValue> item = byId.get(itemId);
            if (item == null) {
                throw new IllegalStateException("Missing item " + itemId + " in " + actual);
            }
            assertItem(item, itemId);
        }
    }

    private static void requireString(
            Map<String, AttributeValue> item,
            String name,
            String expected) {
        AttributeValue actual = item.get(name);
        if (actual == null || !expected.equals(actual.s())) {
            throw new IllegalStateException(
                    "Unexpected item for " + name + ": " + item
            );
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private static double positiveDoubleEnv(String name, double defaultValue) {
        String raw = env(name, Double.toString(defaultValue));
        double value = Double.parseDouble(raw);
        if (!(value > 0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a positive number, got " + raw);
        }
        return value;
    }

    private static int positiveIntEnv(String name, int defaultValue) {
        String raw = env(name, Integer.toString(defaultValue));
        int value = Integer.parseInt(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, got " + raw);
        }
        return value;
    }
}
