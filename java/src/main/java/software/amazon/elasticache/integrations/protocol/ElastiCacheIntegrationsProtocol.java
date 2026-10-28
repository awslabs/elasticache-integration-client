package software.amazon.elasticache.integrations.protocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsOverridesRejected;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsRejectedError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.ResolvedConfig;
import software.amazon.elasticache.integrations.resp.RespArray;
import software.amazon.elasticache.integrations.resp.RespBulkString;
import software.amazon.elasticache.integrations.resp.RespError;
import software.amazon.elasticache.integrations.resp.RespInteger;
import software.amazon.elasticache.integrations.resp.RespNull;
import software.amazon.elasticache.integrations.resp.RespSimpleString;
import software.amazon.elasticache.integrations.resp.RespValue;

/** Cache-specific protocol serialization/deserialization. */
public final class ElastiCacheIntegrationsProtocol {

    /** Command that exchanges config overrides and returns the resolved config. */
    public static final String CONFIG_COMMAND = "aws.config";

    /** Command that sends one HTTP request through Cache. */
    public static final String REQUEST_COMMAND = "aws.request";

    /** Push kind that carries a deferred response for a request ID. */
    public static final String PUSH_KIND_RESPONSE = "aws.response";

    /**
     * Protocol version this client speaks, sent in every {@code aws.config} overrides payload.
     *
     * <p>Mandatory server-side: an exchange without it is rejected rather than defaulted. The proxy
     * answers in the newest version it supports at or below this one, so a client ahead of its proxy
     * is clamped down rather than refused -- and learns which version it got from {@link
     * software.amazon.elasticache.integrations.model.ResolvedConfig#getVersion()}, which carries the
     * <em>negotiated</em> version, not an echo of this.
     */
    public static final String PROTOCOL_VERSION = "1.0";

    /** Key the protocol version is sent under inside the overrides object. */
    private static final String VERSION_KEY = "version";

    /** RESP error code the proxy returns when it rejects the {@code aws.config} overrides. */
    public static final String CONFIG_INVALID_CODE = "CONFIG_INVALID";

    private static final Set<String> EXCLUDED_HEADERS;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final BigInteger MAX_REFRESH_INTERVAL = BigInteger.valueOf(Integer.MAX_VALUE);

    static {
        Set<String> excluded = new HashSet<>();
        excluded.add("authorization");
        excluded.add("x-amz-date");
        excluded.add("x-amz-security-token");
        excluded.add("x-amz-request-id");
        excluded.add("amz-sdk-request");
        EXCLUDED_HEADERS = Collections.unmodifiableSet(excluded);
    }

    private ElastiCacheIntegrationsProtocol() {}

    /**
     * Produce the deterministic normalized header string as UTF-8 bytes.
     *
     * <p>Lowercase names, drop excluded headers, trim values, sort by name, and join {@code
     * name:value} with LF. Returns an empty array when no headers remain.
     *
     * @param headers the request headers, or {@code null}
     * @return the normalized headers, empty when there are none
     */
    public static byte[] normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return new byte[0];
        }

        // Use a TreeMap to sort by lowercased name
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT).trim();
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
     * Format metadata as {@code "service=<svc>&op=<op>"} encoded UTF-8.
     *
     * @param service the AWS service name, or {@code null} for empty
     * @param operation the SDK operation name, or {@code null} for empty
     * @return the encoded metadata
     */
    public static byte[] formatMetadata(String service, String operation) {
        String svc = service != null ? service : "";
        String op = operation != null ? operation : "";
        String result = "service=" + svc + "&op=" + op;
        return result.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Build the {@code aws.config} command.
     *
     * <p>{@code overrides == null} means "unchanged since last send" and sends {@code aws.config}
     * with zero arguments. That form carries no JSON, so it carries no version either, and the server
     * answers it from the version it recorded on the connection's last overrides exchange.
     *
     * <p>Every non-null map, including an empty one, carries {@link #PROTOCOL_VERSION}, which the
     * server requires. It is inserted first so a caller's own {@code version} entry wins, which is
     * how a test drives a version this client would not otherwise send. An empty map is therefore
     * sent as <code>{"version":"1.0"}</code> rather than <code>{}</code> -- the same "use your
     * defaults" intent, now carrying the mandatory field.
     *
     * <p>The caller's map is never mutated: the version is applied to a copy. A {@link LinkedHashMap}
     * keeps the version first in the serialized output, which only matters for reading a payload in a
     * log.
     *
     * @param overrides the config overrides, or {@code null} to send no arguments
     * @return the {@code aws.config} command
     */
    public static SerializedCommand serializeConfig(Map<String, Object> overrides) {
        if (overrides == null) {
            return new SerializedCommand(CONFIG_COMMAND, Collections.emptyList());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(VERSION_KEY, PROTOCOL_VERSION);
        payload.putAll(overrides);
        try {
            byte[] jsonBytes = OBJECT_MAPPER.writeValueAsBytes(payload);
            return new SerializedCommand(CONFIG_COMMAND, Collections.singletonList(jsonBytes));
        } catch (Exception e) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Failed to serialize config overrides: " + e.getMessage(), e);
        }
    }

    /**
     * Parse the {@code aws.config} reply (a JSON bulk string) into a {@link ResolvedConfig}. A
     * RESP-array shape is intentionally not supported.
     *
     * @param value the reply to {@code aws.config}
     * @return the resolved config
     * @throws ElastiCacheIntegrationsOverridesRejected the reply is a {@code CONFIG_INVALID} error
     */
    public static ResolvedConfig parseConfigResponse(RespValue value) {
        if (value instanceof RespError) {
            RespError error = (RespError) value;
            if (CONFIG_INVALID_CODE.equals(error.getCode())) {
                throw new ElastiCacheIntegrationsOverridesRejected(
                        "aws.config rejected: " + error.getCode() + " " + error.getMessage());
            }
            throw new ElastiCacheIntegrationsError(
                    "Server error: " + error.getCode() + " " + error.getMessage());
        }
        if (value instanceof RespArray) {
            throw new ElastiCacheIntegrationsProtocolError("Unexpected array response for config");
        }

        if (!(value instanceof RespBulkString)) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Unexpected response type for config: " + value);
        }
        byte[] data = ((RespBulkString) value).getValue();

        try {
            String json = decodeUtf8(data, "config JSON");
            Map<String, Object> raw =
                    OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
            String version = raw.containsKey("version") ? String.valueOf(raw.get("version")) : null;
            Integer refreshInterval = parseRefreshInterval(raw.get("refresh_interval"));
            return new ResolvedConfig(version, refreshInterval, raw);
        } catch (ElastiCacheIntegrationsProtocolError e) {
            throw e;
        } catch (Exception e) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Failed to parse config JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Validate the {@code refresh_interval} field: required, an integer, and positive.
     *
     * <p>{@code refresh_interval} feeds directly into the config refresher's jittered delay
     * arithmetic, so a missing, wrongly-typed, or non-positive value is considered malformed. Jackson
     * maps JSON integers to Integer/Long/BigInteger and JSON reals to Double, so an integral JSON
     * number is the only accepted shape: {@code 60.0} is a real, not an integer, and is rejected
     * rather than truncated. Values outside the Integer range are rejected rather than wrapped.
     */
    private static Integer parseRefreshInterval(Object value) {
        if (value == null) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Config JSON missing required field 'refresh_interval'");
        }
        BigInteger seconds;
        if (value instanceof BigInteger) {
            seconds = (BigInteger) value;
        } else if (value instanceof Integer
                || value instanceof Long
                || value instanceof Short
                || value instanceof Byte) {
            seconds = BigInteger.valueOf(((Number) value).longValue());
        } else {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Invalid 'refresh_interval': expected int, got "
                            + value.getClass().getSimpleName()
                            + ": "
                            + value);
        }
        if (seconds.signum() <= 0) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Invalid 'refresh_interval': must be positive, got " + seconds);
        }
        if (seconds.compareTo(MAX_REFRESH_INTERVAL) > 0) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Invalid 'refresh_interval': exceeds the supported range, got " + seconds);
        }
        return seconds.intValue();
    }

    /**
     * Build the complete {@code aws.request} command from an {@link ElastiCacheIntegrationsRequest}.
     *
     * <p>The arguments are {@code [method, url, normalized_headers, body, metadata]}.
     *
     * @param request the request to serialize
     * @return the {@code aws.request} command
     */
    public static SerializedCommand serializeRequest(ElastiCacheIntegrationsRequest request) {
        byte[] method = request.getMethod().getBytes(StandardCharsets.UTF_8);
        byte[] url = request.getUrl().getBytes(StandardCharsets.UTF_8);
        byte[] normalizedHeaders = normalizeHeaders(request.getHeaders());
        byte[] body = request.getBody();
        byte[] metadata =
                formatMetadata(request.getMetadata().getService(), request.getMetadata().getOperation());

        List<byte[]> args = new ArrayList<>(5);
        args.add(method);
        args.add(url);
        args.add(normalizedHeaders);
        args.add(body);
        args.add(metadata);
        return new SerializedCommand(REQUEST_COMMAND, args);
    }

    /**
     * Interpret an {@code aws.request} reply into an {@link ElastiCacheIntegrationsResponse}. Expects
     * a 3-element array {@code [status, headers, body]} and validates a status of 100-599.
     *
     * @param value the reply to {@code aws.request}
     * @return the parsed response
     */
    public static ElastiCacheIntegrationsResponse parseRequestResponse(RespValue value) {
        if (value instanceof RespError) {
            RespError error = (RespError) value;
            throw new ElastiCacheIntegrationsRejectedError(
                    error.getCode(), "Server error: " + error.getCode() + " " + error.getMessage());
        }
        if (!(value instanceof RespArray)) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Expected array response, got: "
                            + (value != null ? value.getClass().getSimpleName() : "null"));
        }

        RespArray array = (RespArray) value;
        return deserializeResponse(array.getElements());
    }

    /**
     * Shared logic for parsing the [status, headers, body] array.
     *
     * @param elements the {@code [status, headers, body]} elements
     * @return the parsed response
     */
    public static ElastiCacheIntegrationsResponse deserializeResponse(List<RespValue> elements) {
        if (elements.size() != 3) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Expected 3-element array [status, headers, body], got " + elements.size() + " elements");
        }

        // Parse status code
        RespValue statusValue = elements.get(0);
        int statusCode;
        if (statusValue instanceof RespInteger) {
            long rawStatus = ((RespInteger) statusValue).getValue();
            if (rawStatus < 100 || rawStatus > 599) {
                throw new ElastiCacheIntegrationsProtocolError("Status code out of range: " + rawStatus);
            }
            statusCode = (int) rawStatus;
        } else if (statusValue instanceof RespBulkString) {
            statusCode = parseStatus(((RespBulkString) statusValue).getValue());
        } else if (statusValue instanceof RespSimpleString) {
            statusCode = parseStatus(((RespSimpleString) statusValue).getValue());
        } else {
            throw new ElastiCacheIntegrationsProtocolError("Non-integer status code: " + statusValue);
        }

        if (statusCode < 100 || statusCode > 599) {
            throw new ElastiCacheIntegrationsProtocolError("Status code out of range: " + statusCode);
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
            throw new ElastiCacheIntegrationsProtocolError(
                    "Unexpected body type: " + bodyValue.getClass().getSimpleName());
        }

        return new ElastiCacheIntegrationsResponse(statusCode, headers, body);
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
        String[] lines = headerStr.split("\n", -1);
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
        return Collections.unmodifiableMap(headers);
    }

    private static int parseStatus(byte[] value) {
        String status = decodeUtf8(value, "response status").trim();
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException e) {
            throw new ElastiCacheIntegrationsProtocolError("Non-integer status code: " + status);
        }
    }

    private static String decodeUtf8(byte[] value, String field) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ElastiCacheIntegrationsProtocolError("Invalid UTF-8 in " + field, e);
        }
    }
}
