package software.amazon.photon.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.ResolvedConfig;
import software.amazon.photon.resp.RespArray;
import software.amazon.photon.resp.RespBulkString;
import software.amazon.photon.resp.RespError;
import software.amazon.photon.resp.RespInteger;
import software.amazon.photon.resp.RespNull;
import software.amazon.photon.resp.RespSimpleString;
import software.amazon.photon.resp.RespValue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Photon-specific protocol serialization/deserialization.
 */
public final class PhotonProtocol {

    public static final String CONFIG_COMMAND = "photon.config";
    public static final String REQUEST_COMMAND = "photon.request";
    public static final String PUSH_KIND_RESPONSE = "photon.response";

    private static final Set<String> EXCLUDED_HEADERS;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        Set<String> excluded = new HashSet<>();
        excluded.add("authorization");
        excluded.add("x-amz-date");
        excluded.add("x-amz-security-token");
        excluded.add("x-amz-request-id");
        excluded.add("amz-sdk-request");
        EXCLUDED_HEADERS = Collections.unmodifiableSet(excluded);
    }

    private PhotonProtocol() {
    }

    /**
     * Normalize headers: lowercase names, trim values, sort by name,
     * exclude certain headers, join as "name:value\n..." encoded UTF-8.
     */
    public static byte[] normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return new byte[0];
        }

        // Use a TreeMap to sort by lowercased name
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase().trim();
            if (EXCLUDED_HEADERS.contains(name)) {
                continue;
            }
            String value = entry.getValue().trim();
            sorted.put(name, value);
        }

        if (sorted.isEmpty()) {
            return new byte[0];
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (!first) {
                sb.append('\n');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
            first = false;
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Format metadata as "service=<svc>&op=<op>" encoded UTF-8.
     */
    public static byte[] formatMetadata(String service, String operation) {
        String svc = service != null ? service : "";
        String op = operation != null ? operation : "";
        String result = "service=" + svc + "&op=" + op;
        return result.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Serialize a config command with optional overrides.
     */
    public static SerializedCommand serializeConfig(Map<String, Object> overrides) {
        try {
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(overrides != null ? overrides : Collections.emptyMap());
            return new SerializedCommand(CONFIG_COMMAND, Collections.singletonList(jsonBytes));
        } catch (Exception e) {
            throw new PhotonProtocolError("Failed to serialize config overrides: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a config response. Expects a JSON bulk string.
     * Rejects arrays and errors.
     */
    public static ResolvedConfig parseConfigResponse(RespValue value) {
        if (value instanceof RespError) {
            RespError error = (RespError) value;
            throw new PhotonProtocolError("Server error: " + error.getCode() + " " + error.getMessage());
        }
        if (value instanceof RespArray) {
            throw new PhotonProtocolError("Unexpected array response for config");
        }

        if (!(value instanceof RespBulkString)) {
            throw new PhotonProtocolError("Unexpected response type for config: " + value);
        }
        byte[] data = ((RespBulkString) value).getValue();

        try {
            String json = decodeUtf8(data, "config JSON");
            Map<String, Object> raw = OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            String version = raw.containsKey("version") ? String.valueOf(raw.get("version")) : null;
            Integer refreshInterval = null;
            if (raw.containsKey("refresh_interval")) {
                Object ri = raw.get("refresh_interval");
                if (ri instanceof Number) {
                    refreshInterval = ((Number) ri).intValue();
                }
            }
            return new ResolvedConfig(version, refreshInterval, raw);
        } catch (PhotonProtocolError e) {
            throw e;
        } catch (Exception e) {
            throw new PhotonProtocolError("Failed to parse config JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Serialize a PhotonRequest to a command.
     */
    public static SerializedCommand serializeRequest(PhotonRequest request) {
        byte[] method = request.getMethod().getBytes(StandardCharsets.UTF_8);
        byte[] url = request.getUrl().getBytes(StandardCharsets.UTF_8);
        byte[] normalizedHeaders = normalizeHeaders(request.getHeaders());
        byte[] body = request.getBody();
        byte[] metadata = formatMetadata(
                request.getMetadata().getService(), request.getMetadata().getOperation());

        List<byte[]> args = new ArrayList<>(5);
        args.add(method);
        args.add(url);
        args.add(normalizedHeaders);
        args.add(body);
        args.add(metadata);
        return new SerializedCommand(REQUEST_COMMAND, args);
    }

    /**
     * Parse a request response. Expects a 3-element array [status, headers, body].
     * Validates status 100-599.
     */
    public static PhotonResponse parseRequestResponse(RespValue value) {
        if (value instanceof RespError) {
            RespError error = (RespError) value;
            throw new PhotonProtocolError("Server error: " + error.getCode() + " " + error.getMessage());
        }
        if (!(value instanceof RespArray)) {
            throw new PhotonProtocolError("Expected array response, got: " + (value != null ? value.getClass().getSimpleName() : "null"));
        }

        RespArray array = (RespArray) value;
        return deserializeResponse(array.getElements());
    }

    /**
     * Shared logic for parsing the [status, headers, body] array.
     */
    public static PhotonResponse deserializeResponse(List<RespValue> elements) {
        if (elements.size() != 3) {
            throw new PhotonProtocolError("Expected 3-element array [status, headers, body], got " + elements.size() + " elements");
        }

        // Parse status code
        RespValue statusValue = elements.get(0);
        int statusCode;
        if (statusValue instanceof RespInteger) {
            long rawStatus = ((RespInteger) statusValue).getValue();
            if (rawStatus < 100 || rawStatus > 599) {
                throw new PhotonProtocolError("Status code out of range: " + rawStatus);
            }
            statusCode = (int) rawStatus;
        } else if (statusValue instanceof RespBulkString) {
            statusCode = parseStatus(((RespBulkString) statusValue).getValue());
        } else if (statusValue instanceof RespSimpleString) {
            statusCode = parseStatus(((RespSimpleString) statusValue).getValue());
        } else {
            throw new PhotonProtocolError("Non-integer status code: " + statusValue);
        }

        if (statusCode < 100 || statusCode > 599) {
            throw new PhotonProtocolError("Status code out of range: " + statusCode);
        }

        // Parse headers
        RespValue headersValue = elements.get(1);
        Map<String, String> headers = parseResponseHeaders(headersValue);

        // Parse body
        RespValue bodyValue = elements.get(2);
        byte[] body;
        if (bodyValue instanceof RespBulkString) {
            body = ((RespBulkString) bodyValue).getValue();
        } else if (bodyValue instanceof RespSimpleString) {
            body = ((RespSimpleString) bodyValue).getValue();
        } else if (bodyValue instanceof RespNull) {
            body = new byte[0];
        } else {
            throw new PhotonProtocolError("Unexpected body type: " + bodyValue.getClass().getSimpleName());
        }

        return new PhotonResponse(statusCode, headers, body);
    }

    private static Map<String, String> parseResponseHeaders(RespValue value) {
        byte[] headerBytes;
        if (value instanceof RespBulkString) {
            headerBytes = ((RespBulkString) value).getValue();
        } else if (value instanceof RespSimpleString) {
            headerBytes = ((RespSimpleString) value).getValue();
        } else {
            return Collections.emptyMap();
        }

        if (headerBytes.length == 0) {
            return Collections.emptyMap();
        }

        String headerStr = decodeUtf8(headerBytes, "response headers");
        String[] lines = headerStr.split("\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) {
                continue;
            }
            String name = line.substring(0, colonIdx).trim();
            String val = line.substring(colonIdx + 1).trim();
            headers.put(name, val);
        }
        return headers;
    }

    private static int parseStatus(byte[] value) {
        String status = decodeUtf8(value, "response status").trim();
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException e) {
            throw new PhotonProtocolError("Non-integer status code: " + status);
        }
    }

    private static String decodeUtf8(byte[] value, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PhotonProtocolError("Invalid UTF-8 in " + field, e);
        }
    }
}
