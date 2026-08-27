package software.amazon.photon.predicate;

import org.junit.jupiter.api.Test;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.RequestMetadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredicateEvaluatorTest {

    private static final List<String> ALL_OPERATIONS = Arrays.asList(
            "GetItem", "BatchGetItem", "Query", "Scan",
            "PutItem", "UpdateItem", "DeleteItem", "BatchWriteItem",
            "TransactGetItems", "TransactWriteItems");

    @Test
    void compilesTheDefaultProxyConfig() {
        assertEquals(1, PredicateEvaluator.compile(defaultConfig()).size());
    }

    @Test
    void missingOrMalformedPredicatesCompileToEmpty() {
        assertEquals(0, PredicateEvaluator.compile(null).size());
        assertEquals(0, PredicateEvaluator.compile(Collections.emptyMap()).size());
        assertEquals(0, PredicateEvaluator.compile(
                singletonMap("predicates", "not-a-list")).size());
    }

    @Test
    void missingExpressionAndUncompilableExpressionsAreDropped() {
        List<Object> predicates = new ArrayList<>();
        predicates.add(singletonMap("vars", singletonMap("ops", ALL_OPERATIONS)));
        predicates.add(predicate("this is not (valid CEL", Collections.emptyMap()));
        predicates.addAll(predicateEntries(defaultConfig()));

        PredicateEvaluator evaluator = PredicateEvaluator.compile(
                singletonMap("predicates", predicates));

        assertEquals(1, evaluator.size());
        assertTrue(evaluator.route(request("GetItem", "dynamodb", "{}")));
    }

    @Test
    void defaultConfigRoutesReadsAndWritesButNotUnsupportedOperations() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(defaultConfig());

        for (String operation : ALL_OPERATIONS) {
            assertTrue(evaluator.route(request(operation, "dynamodb", "{}")), operation);
        }
        for (String operation : Arrays.asList(
                "CreateTable", "DescribeTable", "ExecuteStatement")) {
            assertFalse(evaluator.route(request(operation, "dynamodb", "{}")), operation);
        }
        assertFalse(evaluator.route(request("GetItem", "s3", "{}")));
    }

    @Test
    void tableAllowlistRoutesSingleTableReadsAndWrites() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(
                tableAllowlistConfig("Users"));

        for (String operation : Arrays.asList(
                "GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem")) {
            assertTrue(evaluator.route(request(
                    operation, "dynamodb", "{\"TableName\":\"Users\"}")), operation);
            assertFalse(evaluator.route(request(
                    operation, "dynamodb", "{\"TableName\":\"Orders\"}")), operation);
        }
        assertFalse(evaluator.route(request("GetItem", "dynamodb", "{\"Key\":{}}")));
    }

    @Test
    void batchAllowlistRequiresEveryTableAndRejectsEmptyBatch() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(
                tableAllowlistConfig("Users", "Sessions"));

        assertTrue(evaluator.route(request("BatchGetItem", "dynamodb",
                "{\"RequestItems\":{\"Users\":{},\"Sessions\":{}}}")));
        assertFalse(evaluator.route(request("BatchGetItem", "dynamodb",
                "{\"RequestItems\":{\"Users\":{},\"Orders\":{}}}")));
        assertTrue(evaluator.route(request("BatchWriteItem", "dynamodb",
                "{\"RequestItems\":{\"Users\":[]}}")));
        assertFalse(evaluator.route(request("BatchWriteItem", "dynamodb",
                "{\"RequestItems\":{\"Users\":[],\"Orders\":[]}}")));
        assertFalse(evaluator.route(request("BatchGetItem", "dynamodb",
                "{\"RequestItems\":{}}")));
    }

    @Test
    void transactionAllowlistRequiresEveryTableAndRejectsEmptyTransaction() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(
                tableAllowlistConfig("Users", "Sessions"));

        assertTrue(evaluator.route(request("TransactGetItems", "dynamodb",
                "{\"TransactItems\":[{\"Get\":{\"TableName\":\"Users\"}},"
                        + "{\"Get\":{\"TableName\":\"Sessions\"}}]}")));
        assertFalse(evaluator.route(request("TransactGetItems", "dynamodb",
                "{\"TransactItems\":[{\"Get\":{\"TableName\":\"Users\"}},"
                        + "{\"Get\":{\"TableName\":\"Orders\"}}]}")));
        assertTrue(evaluator.route(request("TransactWriteItems", "dynamodb",
                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"Users\"}}]}")));
        assertFalse(evaluator.route(request("TransactWriteItems", "dynamodb",
                "{\"TransactItems\":[{\"Put\":{\"TableName\":\"Orders\"}}]}")));
        assertFalse(evaluator.route(request("TransactGetItems", "dynamodb",
                "{\"TransactItems\":[]}")));
    }

    @Test
    void uninspectableAndOversizedBodiesDoNotRoute() {
        PredicateEvaluator evaluator = PredicateEvaluator.compile(
                tableAllowlistConfig("Users"));

        for (String body : Arrays.asList(
                "", "not json", "[\"TableName\"]", "null",
                "{\"TableName\":\"Users\"} trailing")) {
            assertFalse(evaluator.route(request("GetItem", "dynamodb", body)), body);
        }

        byte[] oversized = new byte[PredicateEvaluator.MAX_BODY_INSPECT_BYTES + 1];
        PhotonRequest request = new PhotonRequest(
                "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(), oversized,
                new RequestMetadata("dynamodb", "GetItem"));
        assertFalse(evaluator.route(request));
    }

    @Test
    void evaluationErrorsCountAsNoMatchAndSiblingsStillEvaluate() {
        Map<String, Object> config = config(
                predicate("body.Missing == 'value'", Collections.emptyMap()),
                predicate("operation == 'GetItem'", Collections.emptyMap()));
        PredicateEvaluator evaluator = PredicateEvaluator.compile(config);

        assertTrue(evaluator.route(request("GetItem", "dynamodb", "{}")));
        assertFalse(evaluator.route(request("PutItem", "dynamodb", "{}")));
    }

    @Test
    void fixedRequestContextAndBoundVariablesAreAvailable() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("prefix", "https://dynamodb.");
        variables.put("expected", "yes");
        PredicateEvaluator evaluator = PredicateEvaluator.compile(config(predicate(
                "method == 'POST' && url.startsWith(prefix) "
                        + "&& headers['x-test'] == expected",
                variables)));
        Map<String, String> headers = singletonMap("x-test", "yes");
        PhotonRequest request = new PhotonRequest(
                "POST", "https://dynamodb.us-east-1.amazonaws.com/",
                headers, new byte[0], new RequestMetadata("dynamodb", "GetItem"));

        assertTrue(evaluator.route(request));
    }

    @Test
    void emptyPredicateSetRoutesNothing() {
        assertFalse(PredicateEvaluator.compile(Collections.emptyMap())
                .route(request("GetItem", "dynamodb", "{}")));
    }

    private static Map<String, Object> defaultConfig() {
        return config(predicate(
                "service == 'dynamodb' && operation in ops",
                singletonMap("ops", ALL_OPERATIONS)));
    }

    private static Map<String, Object> tableAllowlistConfig(String... tables) {
        List<String> allowed = Arrays.asList(tables);
        List<String> singleOperations = Arrays.asList(
                "GetItem", "Query", "Scan", "PutItem", "UpdateItem", "DeleteItem");
        List<String> batchOperations = Arrays.asList("BatchGetItem", "BatchWriteItem");
        List<String> transactionOperations = Arrays.asList(
                "TransactGetItems", "TransactWriteItems");

        return config(
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.TableName) && body.TableName in allowed",
                        vars("ops", singleOperations, "allowed", allowed)),
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.RequestItems) && size(body.RequestItems) > 0 "
                                + "&& body.RequestItems.all(t, t in allowed)",
                        vars("ops", batchOperations, "allowed", allowed)),
                predicate(
                        "service == 'dynamodb' && operation in ops "
                                + "&& has(body.TransactItems) && size(body.TransactItems) > 0 "
                                + "&& body.TransactItems.all(i, "
                                + "i.all(k, i[k].TableName in allowed))",
                        vars("ops", transactionOperations, "allowed", allowed)));
    }

    @SafeVarargs
    private static Map<String, Object> config(Map<String, Object>... predicates) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("version", "1.0");
        config.put("predicates", new ArrayList<>(Arrays.asList(predicates)));
        return config;
    }

    private static Map<String, Object> predicate(
            String expression, Map<String, Object> variables) {
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

    private static PhotonRequest request(String operation, String service, String body) {
        return new PhotonRequest(
                "POST",
                "https://dynamodb.us-east-1.amazonaws.com/",
                Collections.emptyMap(),
                body.getBytes(StandardCharsets.UTF_8),
                new RequestMetadata(service, operation));
    }

    private static <K, V> Map<K, V> singletonMap(K key, V value) {
        Map<K, V> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
