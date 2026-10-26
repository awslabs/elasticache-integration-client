package software.amazon.elasticache.integrations.predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigUnevaluable;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.RequestMetadata;

class PredicateEvaluatorTest {

    private static final List<String> ALL_OPERATIONS =
            Arrays.asList(
                    "GetItem",
                    "BatchGetItem",
                    "Query",
                    "Scan",
                    "PutItem",
                    "UpdateItem",
                    "DeleteItem",
                    "BatchWriteItem",
                    "TransactGetItems",
                    "TransactWriteItems");
    private static final List<String> SINGLE_TABLE_OPERATIONS =
            Arrays.asList("GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem");

    @Test
    void compilesTheDefaultProxyConfig() {
        assertEquals(1, PredicateEvaluator.compile(defaultConfig()).size());
    }

    @Test
    void missingPredicatesCompileToEmpty() {
        assertEquals(0, PredicateEvaluator.compile(null).size());
        assertEquals(0, PredicateEvaluator.compile(Collections.emptyMap()).size());
    }

    @Test
    void nonListPredicatesRejectTheConfig() {
        List<Object> nonLists = Arrays.asList("nope", singletonMap("expr", "true"), null);

        for (Object value : nonLists) {
            assertThrows(
                    ElastiCacheIntegrationsConfigUnevaluable.class,
                    () -> PredicateEvaluator.compile(singletonMap("predicates", value)),
                    String.valueOf(value));
        }
    }

    // One bad entry taints the whole config: caching by the entries that did compile would route
    // a different set of requests than the proxy resolved.
    @Test
    void anyUnevaluableEntryRejectsTheWholeConfig() {
        Map<Object, Object> nonStringKeyVariables = new LinkedHashMap<>();
        nonStringKeyVariables.put(7, "value");
        List<Object> badEntries =
                Arrays.asList(
                        "not-an-object",
                        singletonMap("vars", Collections.emptyMap()),
                        predicate("this is not (valid CEL", Collections.emptyMap()),
                        predicate("operation == 1", Collections.emptyMap()),
                        predicate("true", nonStringKeyVariables));

        for (Object bad : badEntries) {
            List<Object> predicates = new ArrayList<>();
            predicates.add(predicate("operation == 'GetItem'", Collections.emptyMap()));
            predicates.add(bad);
            assertThrows(
                    ElastiCacheIntegrationsConfigUnevaluable.class,
                    () -> PredicateEvaluator.compile(singletonMap("predicates", predicates)),
                    String.valueOf(bad));
        }
    }

    @Test
    void nonObjectVarsAreTreatedAsNoVars() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("expr", "operation == 'GetItem'");
        entry.put("vars", "not-an-object");

        PredicateEvaluator evaluator = PredicateEvaluator.compile(config(entry));

        assertTrue(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", "{}")));
    }

    @Test
    void defaultConfigRoutesReadsAndWritesButNotUnsupportedOperations() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(defaultConfig());

        for (String operation : ALL_OPERATIONS) {
            assertTrue(evaluator.shouldRouteToCache(request(operation, "dynamodb", "{}")), operation);
        }
        for (String operation : Arrays.asList("CreateTable", "DescribeTable", "ExecuteStatement")) {
            assertFalse(evaluator.shouldRouteToCache(request(operation, "dynamodb", "{}")), operation);
        }
        assertFalse(evaluator.shouldRouteToCache(request("GetItem", "s3", "{}")));
    }

    @Test
    void tableAllowlistRoutesSingleTableReadsAndWrites() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(tableAllowlistConfig("Users"));

        for (String operation : SINGLE_TABLE_OPERATIONS) {
            assertTrue(
                    evaluator.shouldRouteToCache(request(operation, "dynamodb", "{\"TableName\":\"Users\"}")),
                    operation);
            assertFalse(
                    evaluator.shouldRouteToCache(
                            request(operation, "dynamodb", "{\"TableName\":\"Orders\"}")),
                    operation);
        }
        assertFalse(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", "{\"Key\":{}}")));
    }

    @Test
    void batchAllowlistRequiresEveryTableAndRejectsEmptyBatch() {
        PredicateEvaluator evaluator =
                PredicateEvaluator.compile(tableAllowlistConfig("Users", "Sessions"));

        assertTrue(
                evaluator.shouldRouteToCache(
                        request(
                                "BatchGetItem", "dynamodb", "{\"RequestItems\":{\"Users\":{},\"Sessions\":{}}}")));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request(
                                "BatchGetItem", "dynamodb", "{\"RequestItems\":{\"Users\":{},\"Orders\":{}}}")));
        assertTrue(
                evaluator.shouldRouteToCache(
                        request("BatchWriteItem", "dynamodb", "{\"RequestItems\":{\"Users\":[]}}")));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request(
                                "BatchWriteItem", "dynamodb", "{\"RequestItems\":{\"Users\":[],\"Orders\":[]}}")));
        assertFalse(
                evaluator.shouldRouteToCache(request("BatchGetItem", "dynamodb", "{\"RequestItems\":{}}")));
    }

    @Test
    void transactionAllowlistRequiresEveryTableAndRejectsEmptyTransaction() {
        PredicateEvaluator evaluator =
                PredicateEvaluator.compile(tableAllowlistConfig("Users", "Sessions"));

        assertTrue(
                evaluator.shouldRouteToCache(
                        request(
                                "TransactGetItems",
                                "dynamodb",
                                "{\"TransactItems\":[{\"Get\":{\"TableName\":\"Users\"}},"
                                        + "{\"Get\":{\"TableName\":\"Sessions\"}}]}")));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request(
                                "TransactGetItems",
                                "dynamodb",
                                "{\"TransactItems\":[{\"Get\":{\"TableName\":\"Users\"}},"
                                        + "{\"Get\":{\"TableName\":\"Orders\"}}]}")));
        assertTrue(
                evaluator.shouldRouteToCache(
                        request(
                                "TransactWriteItems",
                                "dynamodb",
                                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"Users\"}}]}")));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request(
                                "TransactWriteItems",
                                "dynamodb",
                                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"Orders\"}}]}")));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request("TransactGetItems", "dynamodb", "{\"TransactItems\":[]}")));
        // The "TableName" guard: an action carrying no table is a clean non-match, not an error.
        assertFalse(
                evaluator.shouldRouteToCache(
                        request(
                                "TransactGetItems", "dynamodb", "{\"TransactItems\":[{\"Get\":{\"Key\":{}}}]}")));
    }

    @Test
    void uninspectableBodiesDoNotRoute() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(tableAllowlistConfig("Users"));

        for (String body :
                Arrays.asList(
                        "", "not json", "[\"TableName\"]", "null", "{\"TableName\":\"Users\"} trailing")) {
            assertFalse(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", body)), body);
        }
    }

    @Test
    void largeValidBodiesAreEvaluatedByPredicates() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(tableAllowlistConfig("Users"));
        byte[] largeBody = matchingBodyOfSize(600 * 1024);

        assertEquals(600 * 1024, largeBody.length);
        assertTrue(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", largeBody)));
    }

    @Test
    void malformedAndNonObjectBodiesDeclineBodyIndependentPredicates() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(defaultConfig());

        assertTrue(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", new byte[0])));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request("GetItem", "dynamodb", "not json".getBytes(StandardCharsets.UTF_8))));
        assertFalse(
                evaluator.shouldRouteToCache(
                        request("GetItem", "dynamodb", "[]".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void evaluationErrorsCountAsNoMatchAndSiblingsStillEvaluate() {
        Map<String, Object> config =
                config(
                        predicate("body.Missing == 'value'", Collections.emptyMap()),
                        predicate("operation == 'GetItem'", Collections.emptyMap()));
        PredicateEvaluator evaluator = PredicateEvaluator.compile(config);

        assertTrue(evaluator.shouldRouteToCache(request("GetItem", "dynamodb", "{}")));
        assertFalse(evaluator.shouldRouteToCache(request("PutItem", "dynamodb", "{}")));
    }

    @Test
    void fixedRequestContextAndBoundVariablesAreAvailable() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("prefix", "https://dynamodb.");
        variables.put("expected", "yes");
        PredicateEvaluator evaluator =
                PredicateEvaluator.compile(
                        config(
                                predicate(
                                        "method == 'POST' && url.startsWith(prefix) "
                                                + "&& headers['x-test'] == expected",
                                        variables)));
        Map<String, String> headers = singletonMap("x-test", "yes");
        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "POST",
                        "https://dynamodb.us-east-1.amazonaws.com/",
                        headers,
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem"));

        assertTrue(evaluator.shouldRouteToCache(request));
    }

    @Test
    void emptyPredicateSetRoutesNothing() {
        assertFalse(
                PredicateEvaluator.compile(Collections.emptyMap())
                        .shouldRouteToCache(request("GetItem", "dynamodb", "{}")));
    }

    private static Map<String, Object> defaultConfig() {
        return config(
                predicate(
                        "service == 'dynamodb' && operation in ops", singletonMap("ops", ALL_OPERATIONS)));
    }

    private static Map<String, Object> tableAllowlistConfig(String... tables) {
        List<String> allowed = Arrays.asList(tables);
        List<String> batchOperations = Arrays.asList("BatchGetItem", "BatchWriteItem");
        List<String> transactionOperations = Arrays.asList("TransactGetItems", "TransactWriteItems");

        return config(
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.TableName) && body.TableName in allowed",
                        vars("ops", SINGLE_TABLE_OPERATIONS, "allowed", allowed)),
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.RequestItems) && size(body.RequestItems) > 0 "
                                + "&& body.RequestItems.all(t, t in allowed)",
                        vars("ops", batchOperations, "allowed", allowed)),
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.TransactItems) && size(body.TransactItems) > 0 "
                                // Each transaction item is a map keyed by its operation.
                                + "&& body.TransactItems.all(i, i.all(k, "
                                + "\"TableName\" in i[k] && i[k].TableName in allowed))",
                        vars("ops", transactionOperations, "allowed", allowed)));
    }

    @SafeVarargs
    private static Map<String, Object> config(Map<String, Object>... predicates) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0");
        config.put("predicates", new ArrayList<>(Arrays.asList(predicates)));
        return config;
    }

    private static Map<String, Object> predicate(String expression, Map<?, ?> variables) {
        Map<String, Object> predicate = new LinkedHashMap<>();
        predicate.put("expr", expression);
        predicate.put("vars", variables);
        return predicate;
    }

    private static Map<String, Object> vars(
            String firstName, Object firstValue, String secondName, Object secondValue) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(firstName, firstValue);
        variables.put(secondName, secondValue);
        return variables;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> predicateEntries(Map<String, Object> config) {
        return (List<Object>) config.get("predicates");
    }

    private static ElastiCacheIntegrationsRequest request(
            String operation, String service, String body) {
        return request(operation, service, body.getBytes(StandardCharsets.UTF_8));
    }

    private static ElastiCacheIntegrationsRequest request(
            String operation, String service, byte[] body) {
        return new ElastiCacheIntegrationsRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                body,
                new RequestMetadata(service, operation));
    }

    private static byte[] matchingBodyOfSize(int size) {
        String prefix = "{\"TableName\":\"Users\",\"Pad\":\"";
        String suffix = "\"}";
        int paddingSize = size - prefix.length() - suffix.length();
        char[] padding = new char[paddingSize];
        Arrays.fill(padding, 'x');
        return (prefix + new String(padding) + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private static <K, V> Map<K, V> singletonMap(K key, V value) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
