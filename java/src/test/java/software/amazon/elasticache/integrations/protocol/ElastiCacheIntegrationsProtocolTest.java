package software.amazon.elasticache.integrations.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.resp.*;

class ElastiCacheIntegrationsProtocolTest {

    // === serialize_request tests ===

    @Test
    void serializeRequest_argCount() {
        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "GET",
                        "https://example.com",
                        Collections.singletonMap("Host", "example.com"),
                        new RequestMetadata("s3", "GetObject"));
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeRequest(request);
        assertEquals(ElastiCacheIntegrationsProtocol.REQUEST_COMMAND, cmd.getCommandName());
        assertEquals(5, cmd.getArgs().size());
    }

    @Test
    void serializeRequest_methodCoercion() {
        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "POST",
                        "https://example.com",
                        Collections.emptyMap(),
                        "body".getBytes(StandardCharsets.UTF_8),
                        new RequestMetadata("svc", "op"));
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeRequest(request);
        assertArrayEquals("POST".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(0));
        assertArrayEquals("https://example.com".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(1));
    }

    @Test
    void serializeRequest_bodyPassedThrough() {
        byte[] body = "request body".getBytes(StandardCharsets.UTF_8);
        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "PUT",
                        "https://example.com",
                        Collections.emptyMap(),
                        body,
                        new RequestMetadata("svc", "op"));
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeRequest(request);
        assertArrayEquals(body, cmd.getArgs().get(3));
    }

    @Test
    void serializeRequest_nullMetadata() {
        ElastiCacheIntegrationsRequest request =
                new ElastiCacheIntegrationsRequest(
                        "GET", "https://example.com", Collections.emptyMap(), null);
        assertNotNull(request.getMetadata());
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeRequest(request);
        assertArrayEquals("service=&op=".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(4));
    }

    // === normalizeHeaders tests ===

    @Test
    void normalizeHeaders_empty() {
        byte[] result = ElastiCacheIntegrationsProtocol.normalizeHeaders(Collections.emptyMap());
        assertEquals(0, result.length);
    }

    @Test
    void normalizeHeaders_null() {
        byte[] result = ElastiCacheIntegrationsProtocol.normalizeHeaders(null);
        assertEquals(0, result.length);
    }

    @Test
    void normalizeHeaders_excludedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer token");
        headers.put("X-Amz-Date", "20210101");
        headers.put("x-amz-security-token", "tok");
        headers.put("x-amz-request-id", "req-id");
        headers.put("amz-sdk-request", "attempt=1");
        byte[] result = ElastiCacheIntegrationsProtocol.normalizeHeaders(headers);
        assertEquals(0, result.length);
    }

    @Test
    void normalizeHeaders_deterministic() {
        Map<String, String> headers1 = new LinkedHashMap<>();
        headers1.put("Content-Type", "application/json");
        headers1.put("Accept", "text/html");
        headers1.put("Host", "example.com");

        Map<String, String> headers2 = new LinkedHashMap<>();
        headers2.put("Host", "example.com");
        headers2.put("Accept", "text/html");
        headers2.put("Content-Type", "application/json");

        assertArrayEquals(
                ElastiCacheIntegrationsProtocol.normalizeHeaders(headers1),
                ElastiCacheIntegrationsProtocol.normalizeHeaders(headers2));

        // Should be sorted by name
        String result =
                new String(
                        ElastiCacheIntegrationsProtocol.normalizeHeaders(headers1), StandardCharsets.UTF_8);
        assertTrue(result.startsWith("accept:"));
        assertTrue(result.contains("content-type:"));
        assertTrue(result.contains("host:"));
    }

    @Test
    void normalizeHeaders_lowercaseAndTrim() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("  Content-Type  ", "  application/json  ");
        byte[] result = ElastiCacheIntegrationsProtocol.normalizeHeaders(headers);
        String resultStr = new String(result, StandardCharsets.UTF_8);
        assertEquals("content-type:application/json", resultStr);
    }

    @Test
    void normalizeHeaders_ignoresDefaultLocale() {
        Locale original = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("If-Match", "etag");
            headers.put("X-Amz-Id-2", "id");
            headers.put("X-Amz-Request-Id", "req-id");
            String result =
                    new String(
                            ElastiCacheIntegrationsProtocol.normalizeHeaders(headers), StandardCharsets.UTF_8);
            assertEquals("if-match:etag\nx-amz-id-2:id", result);
        } finally {
            Locale.setDefault(original);
        }
    }

    // === formatMetadata tests ===

    @Test
    void formatMetadata_both() {
        byte[] result = ElastiCacheIntegrationsProtocol.formatMetadata("s3", "GetObject");
        assertEquals("service=s3&op=GetObject", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void formatMetadata_nulls() {
        byte[] result = ElastiCacheIntegrationsProtocol.formatMetadata(null, null);
        assertEquals("service=&op=", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void formatMetadata_partialNull() {
        byte[] result = ElastiCacheIntegrationsProtocol.formatMetadata("ec2", null);
        assertEquals("service=ec2&op=", new String(result, StandardCharsets.UTF_8));
    }

    // === parseConfigResponse tests ===

    @Test
    void parseConfigResponse_validJson() {
        String json = "{\"version\":\"1.0\",\"refresh_interval\":30}";
        RespBulkString value = new RespBulkString(json.getBytes(StandardCharsets.UTF_8));
        ResolvedConfig config = ElastiCacheIntegrationsProtocol.parseConfigResponse(value);
        assertEquals("1.0", config.getVersion());
        assertEquals(30, config.getRefreshInterval());
        assertNotNull(config.getRaw());
    }

    @Test
    void parseConfigResponse_missingVersion_defaultsToNull() {
        // 'version' is optional; 'refresh_interval' is required, so it must still be present.
        String json = "{\"refresh_interval\":60,\"predicates\":[]}";
        ResolvedConfig config =
                ElastiCacheIntegrationsProtocol.parseConfigResponse(
                        new RespBulkString(json.getBytes(StandardCharsets.UTF_8)));
        assertNull(config.getVersion());
        assertEquals(Integer.valueOf(60), config.getRefreshInterval());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 60, 3600, Integer.MAX_VALUE})
    void parseConfigResponse_acceptsAnyPositiveInteger(int seconds) {
        // Any positive integer is accepted
        String json = "{\"refresh_interval\":" + seconds + "}";
        ResolvedConfig config =
                ElastiCacheIntegrationsProtocol.parseConfigResponse(
                        new RespBulkString(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Integer.valueOf(seconds), config.getRefreshInterval());
    }

    /**
     * An invalid refresh_interval must fail parsing here rather than crash a background refresher
     * later, and an integral-looking real such as 60.0 must be rejected, not truncated to 60.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"predicates\":[]}", // missing entirely
                "{\"refresh_interval\":null}", // explicit null
                "{\"refresh_interval\":\"60\"}", // wrong type: string
                "{\"refresh_interval\":true}", // wrong type: boolean
                "{\"refresh_interval\":false}", // wrong type: boolean
                "{\"refresh_interval\":60.0}", // wrong type: real
                "{\"refresh_interval\":60.9}", // wrong type: real, would truncate to 60
                "{\"refresh_interval\":0.5}", // wrong type: real, would truncate to 0
                "{\"refresh_interval\":[60]}", // wrong type: array
                "{\"refresh_interval\":{\"seconds\":60}}", // wrong type: object
                "{\"refresh_interval\":0}", // not positive
                "{\"refresh_interval\":-1}", // not positive
                "{\"refresh_interval\":-60}", // not positive
                "{\"refresh_interval\":2147483648}", // Integer.MAX_VALUE + 1
                "{\"refresh_interval\":9223372036854775808}" // beyond long: BigInteger
            })
    void parseConfigResponse_rejectsInvalidRefreshInterval(String json) {
        RespBulkString value = new RespBulkString(json.getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_malformedJson() {
        RespBulkString value = new RespBulkString("not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_nonObject() {
        // JSON array instead of object
        RespBulkString value = new RespBulkString("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_errorFrame() {
        RespError error = new RespError("ERR", "something failed");
        ElastiCacheIntegrationsError thrown =
                assertThrows(
                        ElastiCacheIntegrationsError.class,
                        () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(error));
        assertFalse(thrown instanceof ElastiCacheIntegrationsProtocolError);
        assertFalse(thrown instanceof ElastiCacheIntegrationsOverridesRejected);
        assertEquals("aws.config failed: ERR something failed", thrown.getMessage());
    }

    @Test
    void parseConfigResponse_configInvalidIsOverridesRejected() {
        RespError error = new RespError("CONFIG_INVALID", "unknown override key");
        ElastiCacheIntegrationsOverridesRejected thrown =
                assertThrows(
                        ElastiCacheIntegrationsOverridesRejected.class,
                        () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(error));
        assertTrue(thrown.getMessage().contains("unknown override key"));
    }

    @Test
    void parseConfigResponse_arrayRejection() {
        RespArray array = new RespArray(Arrays.asList(new RespInteger(1)));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(array));
    }

    @Test
    void parseConfigResponse_rejectsSimpleString() {
        RespSimpleString value = new RespSimpleString("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_rejectsInvalidUtf8() {
        RespBulkString value = new RespBulkString(new byte[] {(byte) 0xff});
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseConfigResponse(value));
    }

    // === parseRequestResponse tests ===

    @Test
    void parseRequestResponse_valid() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString("content-type:application/json".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString("response body".getBytes(StandardCharsets.UTF_8)));
        RespArray array = new RespArray(elements);
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.parseRequestResponse(array);
        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("content-type"));
        assertArrayEquals("response body".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void parseRequestResponse_nonArray() {
        RespBulkString value = new RespBulkString("not an array".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.parseRequestResponse(value));
    }

    @Test
    void parseRequestResponse_errorFrame() {
        RespError error = new RespError("CACHE", "miss");
        ElastiCacheIntegrationsRejectedError failure =
                assertThrows(
                        ElastiCacheIntegrationsRejectedError.class,
                        () -> ElastiCacheIntegrationsProtocol.parseRequestResponse(error));
        assertEquals("CACHE", failure.getCode());
    }

    @Test
    void parseRequestResponse_errorFrameMessage() {
        RespError error = new RespError("UNSUPPORTED", "unsupported operation: Foo");
        ElastiCacheIntegrationsRejectedError failure =
                assertThrows(
                        ElastiCacheIntegrationsRejectedError.class,
                        () -> ElastiCacheIntegrationsProtocol.parseRequestResponse(error));
        assertEquals(
                "aws.request failed: UNSUPPORTED unsupported operation: Foo", failure.getMessage());
    }

    @Test
    void parseRequestResponse_codeOnlyErrorFrameMessage() {
        RespError error = new RespError("UNSUPPORTED", "");
        ElastiCacheIntegrationsRejectedError failure =
                assertThrows(
                        ElastiCacheIntegrationsRejectedError.class,
                        () -> ElastiCacheIntegrationsProtocol.parseRequestResponse(error));
        assertEquals("aws.request failed: UNSUPPORTED", failure.getMessage());
    }

    // === deserializeResponse tests ===

    @Test
    void deserializeResponse_wrongShape_tooFew() {
        List<RespValue> elements = Arrays.asList(new RespInteger(200));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals(
                "Malformed request response: expected 3-element array, got 1 elements", error.getMessage());
    }

    @Test
    void deserializeResponse_wrongShape_tooMany() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString(new byte[0]),
                        new RespBulkString(new byte[0]),
                        new RespBulkString(new byte[0]));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_nonIntegerStatus() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespBulkString("not_a_number".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(new byte[0]),
                        new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals("Invalid status code: not_a_number", error.getMessage());
    }

    @Test
    void deserializeResponse_outOfRangeStatus_low() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(99), new RespBulkString(new byte[0]), new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals("Status code out of range (100-599): 99", error.getMessage());
    }

    @Test
    void deserializeResponse_outOfRangeStatus_high() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(600), new RespBulkString(new byte[0]), new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals("Status code out of range (100-599): 600", error.getMessage());
    }

    @Test
    void deserializeResponse_skipsMalformedHeaderLinesAndTrimsValues() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString(
                                " content-type : application/json \nno-colon-here"
                                        .getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.deserializeResponse(elements);
        assertEquals(
                Collections.singletonMap("content-type", "application/json"), response.getHeaders());
    }

    @Test
    void deserializeResponse_rejectsInvalidUtf8InHeaders() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString(new byte[] {(byte) 0xff}),
                        new RespBulkString(new byte[0]));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_acceptsWhitespaceAroundStringStatus() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespBulkString(" 200 ".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(new byte[0]),
                        new RespBulkString(new byte[0]));
        assertEquals(
                200, ElastiCacheIntegrationsProtocol.deserializeResponse(elements).getStatusCode());
    }

    @Test
    void deserializeResponse_emptyHeaders() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespBulkString(new byte[0]),
                        new RespBulkString("body".getBytes(StandardCharsets.UTF_8)));
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.deserializeResponse(elements);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getHeaders().isEmpty());
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void deserializeResponse_strInputStatus() {
        // Status as bulk string "200" should work
        List<RespValue> elements =
                Arrays.asList(
                        new RespBulkString("200".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString("host:example.com".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.deserializeResponse(elements);
        assertEquals(200, response.getStatusCode());
        assertEquals("example.com", response.getHeaders().get("host"));
    }

    @Test
    void deserializeResponse_boundaryStatus_100() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(100), new RespBulkString(new byte[0]), new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.deserializeResponse(elements);
        assertEquals(100, response.getStatusCode());
    }

    @Test
    void deserializeResponse_boundaryStatus_599() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(599), new RespBulkString(new byte[0]), new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsResponse response =
                ElastiCacheIntegrationsProtocol.deserializeResponse(elements);
        assertEquals(599, response.getStatusCode());
    }

    // === serializeConfig tests ===

    @Test
    void deserializeResponse_unexpectedBodyType_throws() {
        List<RespValue> elements =
                Arrays.asList(new RespInteger(200), new RespBulkString(new byte[0]), new RespInteger(42));
        assertThrows(
                ElastiCacheIntegrationsProtocolError.class,
                () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_simpleStringHeaders_throws() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(200),
                        new RespSimpleString("x-a:1".getBytes(StandardCharsets.UTF_8)),
                        new RespBulkString(new byte[0]));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals(
                "Malformed request response elements: headers=RespSimpleString, body=RespBulkString",
                error.getMessage());
    }

    @Test
    void deserializeResponse_checksElementsBeforeStatus() {
        List<RespValue> elements =
                Arrays.asList(
                        new RespInteger(999),
                        new RespBulkString(new byte[0]),
                        new RespSimpleString("ok".getBytes(StandardCharsets.UTF_8)));
        ElastiCacheIntegrationsProtocolError error =
                assertThrows(
                        ElastiCacheIntegrationsProtocolError.class,
                        () -> ElastiCacheIntegrationsProtocol.deserializeResponse(elements));
        assertEquals(
                "Malformed request response elements: headers=RespBulkString, body=RespSimpleString",
                error.getMessage());
    }

    @Test
    void serializeConfig_escapesControlCharactersInLowercaseHex() {
        SerializedCommand command =
                ElastiCacheIntegrationsProtocol.serializeConfig(
                        Collections.singletonMap("name", (Object) "a\u001fb"));
        assertEquals(
                "{\"version\":\"1.0\",\"name\":\"a\\u001fb\"}",
                new String(command.getArgs().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void serializeConfig_withOverrides() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("key", "value");
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeConfig(overrides);
        assertEquals(ElastiCacheIntegrationsProtocol.CONFIG_COMMAND, cmd.getCommandName());
        assertEquals(1, cmd.getArgs().size());
        // Should be valid JSON
        String json = new String(cmd.getArgs().get(0), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"value\""));
    }

    @Test
    void serializeConfig_null_sendsNoArgumentForm() {
        // null means "unchanged since last send"; distinct from an explicit {}, which the
        // server treats as a reset to its defaults.
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeConfig(null);
        assertEquals(ElastiCacheIntegrationsProtocol.CONFIG_COMMAND, cmd.getCommandName());
        assertEquals(0, cmd.getArgs().size());
    }

    @Test
    void serializeConfig_emptyMap_sendsTheVersionOnly() {
        // The server requires a version, so "use your defaults" is the version alone.
        SerializedCommand cmd = ElastiCacheIntegrationsProtocol.serializeConfig(Collections.emptyMap());
        assertEquals(ElastiCacheIntegrationsProtocol.CONFIG_COMMAND, cmd.getCommandName());
        assertEquals(1, cmd.getArgs().size());
        assertEquals(
                "{\"version\":\"" + ElastiCacheIntegrationsProtocol.PROTOCOL_VERSION + "\"}",
                new String(cmd.getArgs().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void serializeConfig_addsTheProtocolVersionToCallerOverrides() {
        SerializedCommand cmd =
                ElastiCacheIntegrationsProtocol.serializeConfig(Collections.singletonMap("item_ttl", 300));
        assertEquals(
                "{\"version\":\""
                        + ElastiCacheIntegrationsProtocol.PROTOCOL_VERSION
                        + "\",\"item_ttl\":300}",
                new String(cmd.getArgs().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void serializeConfig_callerCanOverrideTheVersion() {
        // The injected version is a default, not a floor: this is how a test drives a
        // version the client would not otherwise send.
        SerializedCommand cmd =
                ElastiCacheIntegrationsProtocol.serializeConfig(Collections.singletonMap("version", "9.9"));
        assertEquals("{\"version\":\"9.9\"}", new String(cmd.getArgs().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void serializeConfig_doesNotMutateTheCallerMap() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("item_ttl", 300);
        ElastiCacheIntegrationsProtocol.serializeConfig(overrides);
        assertEquals(Collections.singletonMap("item_ttl", 300), overrides);
    }
}
