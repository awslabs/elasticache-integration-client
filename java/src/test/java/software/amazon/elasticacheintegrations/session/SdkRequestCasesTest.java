package software.amazon.photon.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.ReceivedRequest;
import software.amazon.photon.mock.Reply;

/**
 * Runs the cross-client SDK request cases in shared/sdk-request-cases.json. Each case goes through
 * the real AWS SDK into the mock cache. Python and Node run the same file through their own SDKs,
 * so a client whose adapter builds a different request fails its own gate.
 */
@Tag("alignment")
class SdkRequestCasesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The SDK call for each case. The Java SDK cannot build a request from JSON, so each case is
     * written out here; the body assertion checks it against the shared {@code params}.
     */
    private static final Map<String, Consumer<DynamoDbClient>> CALLS = new HashMap<>();

    static {
        CALLS.put(
                "dynamodb_get_item",
                ddb -> ddb.getItem(b -> b.tableName("Music").key(key("Artist", "Acme"))));
        CALLS.put(
                "dynamodb_query_with_expression_values",
                ddb ->
                        ddb.query(
                                b ->
                                        b.tableName("Music")
                                                .keyConditionExpression("Artist = :a")
                                                .expressionAttributeValues(key(":a", "Acme"))
                                                .limit(10)));
        CALLS.put(
                "dynamodb_batch_get_item",
                ddb ->
                        ddb.batchGetItem(
                                b ->
                                        b.requestItems(
                                                Collections.singletonMap(
                                                        "Music",
                                                        KeysAndAttributes.builder()
                                                                .keys(Collections.singletonList(key("Artist", "Acme")))
                                                                .build()))));
        CALLS.put(
                "dynamodb_put_item",
                ddb -> {
                    Map<String, AttributeValue> item = new LinkedHashMap<>(key("Artist", "Acme"));
                    item.put("Year", AttributeValue.builder().n("2020").build());
                    ddb.putItem(b -> b.tableName("Music").item(item));
                });
    }

    @TestFactory
    Stream<DynamicTest> sdkRequestCases() throws Exception {
        JsonNode file = loadCases();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : file.get("cases")) {
            tests.add(
                    DynamicTest.dynamicTest(
                            testCase.get("name").asText(), () -> assertSends(file, testCase)));
        }
        return tests.stream();
    }

    @Test
    void everySharedCaseHasAnSdkCall() throws Exception {
        Set<String> names = new HashSet<>();
        for (JsonNode testCase : loadCases().get("cases")) {
            names.add(testCase.get("name").asText());
        }
        assertEquals(names, CALLS.keySet());
    }

    /** This runner skips any field it doesn't know, so an unknown or missing field fails here. */
    @Test
    void casesUseOnlyKnownFields() throws Exception {
        JsonNode file = loadCases();
        assertEquals(keys("cases", "config", "description", "excluded_headers"), keys(file));
        for (JsonNode testCase : file.get("cases")) {
            assertEquals(keys("expected", "name", "operation", "params", "service"), keys(testCase));
            assertEquals(keys("headers", "metadata", "method", "url"), keys(testCase.get("expected")));
        }
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new HashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static Set<String> keys(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    private static void assertSends(JsonNode file, JsonNode testCase) throws Exception {
        Consumer<DynamoDbClient> call = CALLS.get(testCase.get("name").asText());
        if (call == null) {
            fail("No SDK call for " + testCase.get("name").asText());
        }
        MockCache cache = new MockCache();
        cache.setConfigJson(MAPPER.writeValueAsString(file.get("config")));
        cache.setReply(
                Reply.ok(
                        200, Collections.singletonMap("content-type", "application/x-amz-json-1.0"), "{}"));
        try (MockTlsServer server = new MockTlsServer(cache);
                PhotonClient.Result<DynamoDbClient> result =
                        PhotonClient.builder()
                                .endpoint("localhost:" + server.getPort())
                                .authCredentials("test-user", "test-token")
                                .sslContext(server.getSslContext())
                                .originHttpClient(new NoOriginClient())
                                .origin(
                                        DynamoDbClient.builder(),
                                        b ->
                                                b.region(Region.US_EAST_1)
                                                        // Java hands Photon the request before SigV4 signing, so no
                                                        // signing header reaches it with any credentials. The encode
                                                        // vectors cover the excluded headers.
                                                        .credentialsProvider(
                                                                StaticCredentialsProvider.create(
                                                                        AwsBasicCredentials.create("testkey", "testsecret"))))
                                .build()) {
            call.accept(result.client());

            assertEquals(1, cache.getRequests().size());
            ReceivedRequest request = cache.getRequests().get(0);
            JsonNode expected = testCase.get("expected");
            assertEquals(expected.get("method").asText(), request.getMethod());
            assertEquals(expected.get("url").asText(), request.getUrl());
            assertEquals(expected.get("metadata").asText(), request.getMetadata());
            assertEquals(testCase.get("params"), MAPPER.readTree(request.getBody()));

            // Normalized headers are one "name:value" line each, so compare whole lines.
            List<String> lines = Arrays.asList(request.getHeaders().split("\n"));
            for (Iterator<String> it = expected.get("headers").fieldNames(); it.hasNext(); ) {
                String name = it.next();
                String line = name + ":" + expected.get("headers").get(name).asText();
                assertTrue(lines.contains(line), "missing " + line);
            }
            for (JsonNode excluded : file.get("excluded_headers")) {
                assertFalse(sendsHeader(lines, excluded.asText()), excluded.asText() + " was sent");
            }
        }
    }

    /**
     * Photon must carry every case; a request that falls back to the origin fails here instead of
     * reaching real AWS.
     */
    private static final class NoOriginClient implements SdkHttpClient {
        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            throw new AssertionError("request went to the origin: " + request.httpRequest().getUri());
        }

        @Override
        public void close() {}
    }

    private static boolean sendsHeader(List<String> lines, String name) {
        return lines.stream().anyMatch(line -> line.startsWith(name + ":"));
    }

    private static Map<String, AttributeValue> key(String name, String value) {
        return Collections.singletonMap(name, AttributeValue.builder().s(value).build());
    }

    private static JsonNode loadCases() throws Exception {
        try (InputStream in =
                SdkRequestCasesTest.class.getResourceAsStream("/sdk-request-cases.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing sdk-request-cases.json test resource");
            }
            return MAPPER.readTree(in);
        }
    }
}
