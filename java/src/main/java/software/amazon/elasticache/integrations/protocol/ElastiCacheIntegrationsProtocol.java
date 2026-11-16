package software.amazon.elasticache.integrations.protocol;

import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    // Escape DEL and everything above it, in lowercase hex, as Python's json.dumps does.
    private static final ObjectMapper OBJECT_MAPPER =
            JsonMapper.builder(
                            new JsonFactoryBuilder()
                                    .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                                    .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
                                    .characterEscapes(new DelEscapes())
                                    .build())
                    .build();
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
     * <p>Lowercase names, drop excluded headers, trim values, sort by name then value, and join
     * {@code name:value} with LF. Two names that lowercase to the same name are both kept. Returns an
     * empty array when no headers remain.
     *
     * @param headers the request headers, or {@code null}
     * @return the normalized headers, empty when there are none
     */
    public static byte[] normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return new byte[0];
        }

        List<String[]> pairs = new ArrayList<>(headers.size());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT).trim();
            if (EXCLUDED_HEADERS.contains(name)) {
                continue;
            }
            pairs.add(new String[] {name, entry.getValue().trim()});
        }
        pairs.sort(
                Comparator.<String[], String>comparing(
                                pair -> pair[0], ElastiCacheIntegrationsProtocol::compareCodePoints)
                        .thenComparing(pair -> pair[1], ElastiCacheIntegrationsProtocol::compareCodePoints));

        StringBuilder sb = new StringBuilder();
        for (String[] pair : pairs) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(pair[0]).append(':').append(pair[1]);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Compare two strings by Unicode code point, as Python's {@code sorted()} does. {@link
     * String#compareTo} compares UTF-16 code units, which orders a supplementary character such as
     * U+10000 before U+E000.
     */
    private static int compareCodePoints(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int leftCodePoint = left.codePointAt(i);
            int rightCodePoint = right.codePointAt(j);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            i += Character.charCount(leftCodePoint);
            j += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - i, right.length() - j);
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
     * <p>Control characters, DEL, and every character above U+007F are written as lowercase hex
     * escapes, as Python's {@code json.dumps} and the Node client write them.
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
            throw new ElastiCacheIntegrationsError("aws.config failed: " + errorText(error));
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
            Object rawVersion = raw.get("version");
            String version = rawVersion == null ? null : String.valueOf(rawVersion);
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
                    error.getCode(), "aws.request failed: " + errorText(error));
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
                    "Malformed request response: expected 3-element array, got "
                            + elements.size()
                            + " elements");
        }

        // Element types are checked before the status, in the same order as the Python client.
        RespValue headersValue = elements.get(1);
        RespValue bodyValue = elements.get(2);
        if (!(headersValue instanceof RespBulkString) || !(bodyValue instanceof RespBulkString)) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Malformed request response elements: headers="
                            + headersValue.getClass().getSimpleName()
                            + ", body="
                            + bodyValue.getClass().getSimpleName());
        }

        RespValue statusValue = elements.get(0);
        long statusCode;
        if (statusValue instanceof RespInteger) {
            statusCode = ((RespInteger) statusValue).getValue();
        } else if (statusValue instanceof RespBulkString) {
            statusCode = parseStatus(((RespBulkString) statusValue).getValue());
        } else if (statusValue instanceof RespSimpleString) {
            statusCode = parseStatus(((RespSimpleString) statusValue).getValue());
        } else {
            throw new ElastiCacheIntegrationsProtocolError("Invalid status code: " + statusValue);
        }
        if (statusCode < 100 || statusCode > 599) {
            throw new ElastiCacheIntegrationsProtocolError(
                    "Status code out of range (100-599): " + statusCode);
        }

        Map<String, String> headers = parseResponseHeaders(((RespBulkString) headersValue).getValue());
        byte[] body = ((RespBulkString) bodyValue).getValue();

        return new ElastiCacheIntegrationsResponse((int) statusCode, headers, body);
    }

    /** The error line as {@code <CODE> <message>}, trimmed. */
    private static String errorText(RespError error) {
        return (error.getCode() + " " + error.getMessage()).trim();
    }

    private static Map<String, String> parseResponseHeaders(byte[] headerBytes) {
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
            throw new ElastiCacheIntegrationsProtocolError("Invalid status code: " + status);
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

    /** JSON's standard escapes plus DEL (U+007F), which JSON allows raw but json.dumps escapes. */
    private static final class DelEscapes extends CharacterEscapes {
        private static final long serialVersionUID = 1L;
        private final int[] asciiEscapes;

        DelEscapes() {
            int[] escapes = CharacterEscapes.standardAsciiEscapesForJSON();
            escapes[0x7F] = CharacterEscapes.ESCAPE_STANDARD;
            asciiEscapes = escapes;
        }

        @Override
        public int[] getEscapeCodesForAscii() {
            return asciiEscapes;
        }

        @Override
        public SerializableString getEscapeSequence(int ch) {
            return null;
        }
    }
}
