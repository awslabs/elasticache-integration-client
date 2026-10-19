package software.amazon.elasticache.integrations.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.resp.DecodeResult;
import software.amazon.elasticache.integrations.resp.RespCodec;

/**
 * Runs the cross-client wire vectors in shared/protocol-vectors.json. Python and Node run the same
 * file, so a client that drifts from the shared bytes fails its own gate.
 */
@Tag("alignment")
class ProtocolVectorsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Vectors Java does not match yet, each mapped to a fragment of the assertion message it fails
     * with today. A gap passes only when it fails with that message; when a fix makes it pass, the
     * test fails until the name is removed here.
     */
    private static final Map<String, String> KNOWN_GAPS = new HashMap<>();

    private static final Map<String, Class<? extends ElastiCacheIntegrationsError>> ERRORS =
            new HashMap<>();

    static {
        ERRORS.put("protocol", ElastiCacheIntegrationsProtocolError.class);
        ERRORS.put("rejected", ElastiCacheIntegrationsRejectedError.class);
        ERRORS.put("overrides_rejected", ElastiCacheIntegrationsOverridesRejected.class);
        ERRORS.put("error", ElastiCacheIntegrationsError.class);
    }

    @TestFactory
    Stream<DynamicTest> encodeVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : loadVectors().get("encode")) {
            tests.add(vectorTest(vector, () -> assertEncodes(vector)));
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> decodeVectors() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : loadVectors().get("decode")) {
            tests.add(vectorTest(vector, () -> assertDecodes(vector)));
        }
        return tests.stream();
    }

    @Test
    void knownGapsNameExistingVectors() throws Exception {
        JsonNode vectors = loadVectors();
        Set<String> names = new HashSet<>();
        for (String section : Arrays.asList("encode", "decode")) {
            for (JsonNode vector : vectors.get(section)) {
                names.add(vector.get("name").asText());
            }
        }
        Set<String> unknown = new HashSet<>(KNOWN_GAPS.keySet());
        unknown.removeAll(names);
        assertEquals(Collections.emptySet(), unknown);
    }

    /** This runner skips any field it doesn't know, so an unknown or missing field fails here. */
    @Test
    void vectorsUseOnlyKnownFields() throws Exception {
        JsonNode vectors = loadVectors();
        assertEquals(keys("decode", "description", "encode"), keys(vectors));
        for (JsonNode vector : vectors.get("encode")) {
            Set<String> fields = keys(vector);
            assertTrue(
                    fields.equals(keys("frame", "name", "request"))
                            || fields.equals(keys("frame", "name", "overrides")),
                    vector.toString());
            if (vector.has("request")) {
                assertEquals(
                        keys("body", "headers", "metadata", "method", "url"), keys(vector.get("request")));
                assertEquals(keys("operation", "service"), keys(vector.get("request").get("metadata")));
            }
        }
        for (JsonNode vector : vectors.get("decode")) {
            Set<String> outcome = keys(vector);
            outcome.removeAll(keys("command", "name", "reply"));
            assertTrue(
                    outcome.equals(keys("response"))
                            || outcome.equals(keys("config"))
                            || outcome.equals(keys("error"))
                            || outcome.equals(keys("code", "error")),
                    vector.toString());
            if (vector.has("response")) {
                assertEquals(keys("body", "headers", "status"), keys(vector.get("response")));
            }
            if (vector.has("config")) {
                assertEquals(keys("raw", "refresh_interval", "version"), keys(vector.get("config")));
            }
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

    private static DynamicTest vectorTest(JsonNode vector, Executable check) {
        String name = vector.get("name").asText();
        String gap = KNOWN_GAPS.get(name);
        if (gap == null) {
            return DynamicTest.dynamicTest(name, check);
        }
        return DynamicTest.dynamicTest(
                name + " (known gap)",
                () -> {
                    try {
                        check.execute();
                    } catch (AssertionError expected) {
                        assertTrue(
                                String.valueOf(expected.getMessage()).contains(gap),
                                name + " failed for another reason: " + expected.getMessage());
                        return;
                    }
                    fail(name + " now matches the shared vector; remove it from KNOWN_GAPS");
                });
    }

    private static void assertEncodes(JsonNode vector) throws Exception {
        SerializedCommand command;
        if (vector.has("request")) {
            JsonNode r = vector.get("request");
            @SuppressWarnings("unchecked")
            Map<String, String> headers = MAPPER.convertValue(r.get("headers"), Map.class);
            command =
                    ElastiCacheIntegrationsProtocol.serializeRequest(
                            new ElastiCacheIntegrationsRequest(
                                    r.get("method").asText(),
                                    r.get("url").asText(),
                                    headers,
                                    utf8(r.get("body").asText()),
                                    new RequestMetadata(
                                            r.get("metadata").get("service").asText(),
                                            r.get("metadata").get("operation").asText())));
        } else {
            JsonNode overrides = vector.get("overrides");
            @SuppressWarnings("unchecked")
            Map<String, Object> map =
                    overrides.isNull() ? null : MAPPER.convertValue(overrides, Map.class);
            command = ElastiCacheIntegrationsProtocol.serializeConfig(map);
        }
        byte[] frame =
                RespCodec.encodeCommand(command.getCommandName(), command.getArgs().toArray(new byte[0][]));
        assertEquals(vector.get("frame").asText(), new String(frame, StandardCharsets.UTF_8));
    }

    private static void assertDecodes(JsonNode vector) throws Exception {
        byte[] replyBytes = utf8(vector.get("reply").asText());
        DecodeResult decoded = RespCodec.decode(replyBytes);
        assertEquals(replyBytes.length, decoded.getPosition());
        boolean isRequest = vector.get("command").asText().equals("aws.request");

        if (vector.has("error")) {
            Executable parse =
                    isRequest
                            ? () -> ElastiCacheIntegrationsProtocol.parseRequestResponse(decoded.getValue())
                            : () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(decoded.getValue());
            ElastiCacheIntegrationsError error = assertThrows(ElastiCacheIntegrationsError.class, parse);
            // "error" accepts any ElastiCacheIntegrationsError; the other kinds name an exact class.
            if (!vector.get("error").asText().equals("error")) {
                assertEquals(ERRORS.get(vector.get("error").asText()), error.getClass());
            }
            if (vector.has("code")) {
                assertEquals(
                        vector.get("code").asText(), ((ElastiCacheIntegrationsRejectedError) error).getCode());
            }
        } else if (vector.has("response")) {
            JsonNode expected = vector.get("response");
            ElastiCacheIntegrationsResponse response =
                    ElastiCacheIntegrationsProtocol.parseRequestResponse(decoded.getValue());
            assertEquals(expected.get("status").asInt(), response.getStatusCode());
            assertEquals(MAPPER.convertValue(expected.get("headers"), Map.class), response.getHeaders());
            assertArrayEquals(utf8(expected.get("body").asText()), response.getBody());
        } else {
            JsonNode expected = vector.get("config");
            ResolvedConfig config =
                    ElastiCacheIntegrationsProtocol.parseConfigResponse(decoded.getValue());
            JsonNode version =
                    config.getVersion() == null
                            ? NullNode.getInstance()
                            : TextNode.valueOf(config.getVersion());
            assertEquals(expected.get("version"), version);
            assertEquals(
                    Integer.valueOf(expected.get("refresh_interval").asInt()), config.getRefreshInterval());
            assertEquals(MAPPER.convertValue(expected.get("raw"), Map.class), config.getRaw());
        }
    }

    private static JsonNode loadVectors() throws Exception {
        try (InputStream in = ProtocolVectorsTest.class.getResourceAsStream("/protocol-vectors.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing protocol-vectors.json test resource");
            }
            return MAPPER.readTree(in);
        }
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
