package software.amazon.photon.protocol;

import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.resp.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PhotonProtocolTest {

    // === serialize_request tests ===

    @Test
    void serializeRequest_argCount() {
        PhotonRequest request = new PhotonRequest("GET", "https://example.com",
                Collections.singletonMap("Host", "example.com"),
                new RequestMetadata("s3", "GetObject"));
        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        assertEquals(PhotonProtocol.REQUEST_COMMAND, cmd.getCommandName());
        assertEquals(5, cmd.getArgs().size());
    }

    @Test
    void serializeRequest_methodCoercion() {
        PhotonRequest request = new PhotonRequest("POST", "https://example.com",
                Collections.emptyMap(), "body".getBytes(StandardCharsets.UTF_8),
                new RequestMetadata("svc", "op"));
        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        assertArrayEquals("POST".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(0));
        assertArrayEquals("https://example.com".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(1));
    }

    @Test
    void serializeRequest_bodyPassedThrough() {
        byte[] body = "request body".getBytes(StandardCharsets.UTF_8);
        PhotonRequest request = new PhotonRequest("PUT", "https://example.com",
                Collections.emptyMap(), body, new RequestMetadata("svc", "op"));
        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        assertArrayEquals(body, cmd.getArgs().get(3));
    }

    @Test
    void serializeRequest_nullMetadata() {
        PhotonRequest request = new PhotonRequest("GET", "https://example.com",
                Collections.emptyMap(), null);
        assertNotNull(request.getMetadata());
        SerializedCommand cmd = PhotonProtocol.serializeRequest(request);
        assertArrayEquals("service=&op=".getBytes(StandardCharsets.UTF_8), cmd.getArgs().get(4));
    }

    // === normalizeHeaders tests ===

    @Test
    void normalizeHeaders_empty() {
        byte[] result = PhotonProtocol.normalizeHeaders(Collections.emptyMap());
        assertEquals(0, result.length);
    }

    @Test
    void normalizeHeaders_null() {
        byte[] result = PhotonProtocol.normalizeHeaders(null);
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
        byte[] result = PhotonProtocol.normalizeHeaders(headers);
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

        assertArrayEquals(PhotonProtocol.normalizeHeaders(headers1),
                PhotonProtocol.normalizeHeaders(headers2));

        // Should be sorted by name
        String result = new String(PhotonProtocol.normalizeHeaders(headers1), StandardCharsets.UTF_8);
        assertTrue(result.startsWith("accept:"));
        assertTrue(result.contains("content-type:"));
        assertTrue(result.contains("host:"));
    }

    @Test
    void normalizeHeaders_lowercaseAndTrim() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("  Content-Type  ", "  application/json  ");
        byte[] result = PhotonProtocol.normalizeHeaders(headers);
        String resultStr = new String(result, StandardCharsets.UTF_8);
        assertEquals("content-type:application/json", resultStr);
    }

    // === formatMetadata tests ===

    @Test
    void formatMetadata_both() {
        byte[] result = PhotonProtocol.formatMetadata("s3", "GetObject");
        assertEquals("service=s3&op=GetObject", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void formatMetadata_nulls() {
        byte[] result = PhotonProtocol.formatMetadata(null, null);
        assertEquals("service=&op=", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    void formatMetadata_partialNull() {
        byte[] result = PhotonProtocol.formatMetadata("ec2", null);
        assertEquals("service=ec2&op=", new String(result, StandardCharsets.UTF_8));
    }

    // === parseConfigResponse tests ===

    @Test
    void parseConfigResponse_validJson() {
        String json = "{\"version\":\"1.0\",\"refresh_interval\":30}";
        RespBulkString value = new RespBulkString(json.getBytes(StandardCharsets.UTF_8));
        ResolvedConfig config = PhotonProtocol.parseConfigResponse(value);
        assertEquals("1.0", config.getVersion());
        assertEquals(30, config.getRefreshInterval());
        assertNotNull(config.getRaw());
    }

    @Test
    void parseConfigResponse_malformedJson() {
        RespBulkString value = new RespBulkString("not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_nonObject() {
        // JSON array instead of object
        RespBulkString value = new RespBulkString("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_errorFrame() {
        RespError error = new RespError("ERR", "something failed");
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(error));
    }

    @Test
    void parseConfigResponse_arrayRejection() {
        RespArray array = new RespArray(Arrays.asList(new RespInteger(1)));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(array));
    }

    @Test
    void parseConfigResponse_rejectsSimpleString() {
        RespSimpleString value = new RespSimpleString("{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(value));
    }

    @Test
    void parseConfigResponse_rejectsInvalidUtf8() {
        RespBulkString value = new RespBulkString(new byte[]{(byte) 0xff});
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseConfigResponse(value));
    }

    // === parseRequestResponse tests ===

    @Test
    void parseRequestResponse_valid() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString("content-type:application/json".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("response body".getBytes(StandardCharsets.UTF_8))
        );
        RespArray array = new RespArray(elements);
        PhotonResponse response = PhotonProtocol.parseRequestResponse(array);
        assertEquals(200, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().get("content-type"));
        assertArrayEquals("response body".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void parseRequestResponse_nonArray() {
        RespBulkString value = new RespBulkString("not an array".getBytes(StandardCharsets.UTF_8));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseRequestResponse(value));
    }

    @Test
    void parseRequestResponse_errorFrame() {
        RespError error = new RespError("CACHE", "miss");
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.parseRequestResponse(error));
    }

    // === deserializeResponse tests ===

    @Test
    void deserializeResponse_wrongShape_tooFew() {
        List<RespValue> elements = Arrays.asList(new RespInteger(200));
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_wrongShape_tooMany() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_nonIntegerStatus() {
        List<RespValue> elements = Arrays.asList(
                new RespBulkString("not_a_number".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_outOfRangeStatus_low() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(99),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_outOfRangeStatus_high() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(600),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_skipsMalformedHeaderLinesAndTrimsValues() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(" content-type : application/json \nno-colon-here".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString(new byte[0])
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(Collections.singletonMap("content-type", "application/json"), response.getHeaders());
    }

    @Test
    void deserializeResponse_rejectsInvalidUtf8InHeaders() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(new byte[]{(byte) 0xff}),
                new RespBulkString(new byte[0])
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void deserializeResponse_acceptsWhitespaceAroundStringStatus() {
        List<RespValue> elements = Arrays.asList(
                new RespBulkString(" 200 ".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        assertEquals(200, PhotonProtocol.deserializeResponse(elements).getStatusCode());
    }

    @Test
    void deserializeResponse_emptyHeaders() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(new byte[0]),
                new RespBulkString("body".getBytes(StandardCharsets.UTF_8))
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getHeaders().isEmpty());
        assertArrayEquals("body".getBytes(StandardCharsets.UTF_8), response.getBody());
    }

    @Test
    void deserializeResponse_strInputStatus() {
        // Status as bulk string "200" should work
        List<RespValue> elements = Arrays.asList(
                new RespBulkString("200".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("host:example.com".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString(new byte[0])
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(200, response.getStatusCode());
        assertEquals("example.com", response.getHeaders().get("host"));
    }

    @Test
    void deserializeResponse_boundaryStatus_100() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(100),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(100, response.getStatusCode());
    }

    @Test
    void deserializeResponse_boundaryStatus_599() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(599),
                new RespBulkString(new byte[0]),
                new RespBulkString(new byte[0])
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(599, response.getStatusCode());
    }

    // === serializeConfig tests ===

    @Test
    void deserializeResponse_nullBody_treatedAsEmpty() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(new byte[0]),
                RespNull.INSTANCE
        );
        PhotonResponse response = PhotonProtocol.deserializeResponse(elements);
        assertEquals(200, response.getStatusCode());
        assertArrayEquals(new byte[0], response.getBody());
    }

    @Test
    void deserializeResponse_unexpectedBodyType_throws() {
        List<RespValue> elements = Arrays.asList(
                new RespInteger(200),
                new RespBulkString(new byte[0]),
                new RespInteger(42)
        );
        assertThrows(PhotonProtocolError.class, () -> PhotonProtocol.deserializeResponse(elements));
    }

    @Test
    void serializeConfig_withOverrides() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("key", "value");
        SerializedCommand cmd = PhotonProtocol.serializeConfig(overrides);
        assertEquals(PhotonProtocol.CONFIG_COMMAND, cmd.getCommandName());
        assertEquals(1, cmd.getArgs().size());
        // Should be valid JSON
        String json = new String(cmd.getArgs().get(0), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"value\""));
    }

    @Test
    void serializeConfig_null() {
        SerializedCommand cmd = PhotonProtocol.serializeConfig(null);
        assertEquals(PhotonProtocol.CONFIG_COMMAND, cmd.getCommandName());
        assertEquals(1, cmd.getArgs().size());
        assertEquals("{}", new String(cmd.getArgs().get(0), StandardCharsets.UTF_8));
    }
}
