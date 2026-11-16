package software.amazon.elasticache.integrations.mock;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsProtocolError;
import software.amazon.elasticache.integrations.resp.*;

/**
 * Mock Cache cache server logic. Implements the command handling contract. This is a pure handler
 * class - no networking.
 */
public class MockCache {

    public enum FailureMode {
        NONE,
        HANG,
        DROP
    }

    private Reply reply = Reply.ok(200, Collections.emptyMap(), new byte[0]);
    private Function<ReceivedRequest, Reply> replyFunction = null;
    private String configJson =
            "{\"version\":\"1.0\",\"refresh_interval\":60,"
                    + "\"predicates\":[{\"expr\":\"service == 'dynamodb' && operation in ops\","
                    + "\"vars\":{\"ops\":[\"GetItem\",\"BatchGetItem\",\"Query\",\"Scan\","
                    + "\"PutItem\",\"UpdateItem\",\"DeleteItem\",\"BatchWriteItem\","
                    + "\"TransactGetItems\",\"TransactWriteItems\"]}}]}";

    /**
     * Reply for a zero-argument ({@code aws.config} with no overrides) request. {@code null} means
     * "unchanged", replied as a RESP3 null; set this to simulate the server changing its own config
     * independently of the client. Mutable between ticks.
     */
    private String noArgConfigReply = null;

    /** Error reply for a zero-argument {@code aws.config}; takes precedence when set. */
    private RespError noArgConfigError = null;

    /** Error reply for a one-argument {@code aws.config}; takes precedence when set. */
    private RespError configError = null;

    private FailureMode failureMode = FailureMode.NONE;
    private String failureCommand = null;
    private boolean failureAfterReply = false;
    private BiFunction<String, String, RespValue> authReplyFunction =
            (userId, token) -> new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8));
    private RespValue helloReply;

    private final List<String> receivedCommands = new ArrayList<>();
    private final List<List<RespValue>> receivedCommandFrames = new ArrayList<>();
    private final List<ReceivedRequest> requests = new ArrayList<>();
    private final List<AuthAttempt> authAttempts = new ArrayList<>();
    private boolean overridesSupplied;

    public void setReply(Reply reply) {
        this.reply = reply;
        this.replyFunction = null; // Clear function when setting static reply
    }

    /** Set a function to generate replies based on the request. This overrides the static reply. */
    public void setReplyFunction(Function<ReceivedRequest, Reply> replyFunction) {
        this.replyFunction = replyFunction;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    /**
     * Set the reply for a zero-argument {@code aws.config} (mutable between ticks). Pass {@code null}
     * to go back to replying with a RESP3 null ("unchanged").
     */
    public void setNoArgConfigReply(String noArgConfigReply) {
        this.noArgConfigReply = noArgConfigReply;
    }

    /** Reply to one-argument {@code aws.config} with an error. Pass {@code null} to clear. */
    public void setConfigError(RespError configError) {
        this.configError = configError;
    }

    /** Reply to zero-argument {@code aws.config} with an error. Pass {@code null} to clear. */
    public void setNoArgConfigError(RespError noArgConfigError) {
        this.noArgConfigError = noArgConfigError;
    }

    /**
     * Whether the most recently handled {@code aws.config} request supplied an overrides argument
     * (the 1-argument form), as opposed to the 0-argument "unchanged" form.
     */
    public boolean wasOverridesSupplied() {
        return overridesSupplied;
    }

    public void setFailureMode(FailureMode mode) {
        this.failureMode = mode;
    }

    public void setFailureCommand(String command) {
        this.failureCommand = command;
    }

    public void setFailureAfterReply(boolean failureAfterReply) {
        this.failureAfterReply = failureAfterReply;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public void setAuthReplyFunction(BiFunction<String, String, RespValue> authReplyFunction) {
        this.authReplyFunction = Objects.requireNonNull(authReplyFunction, "authReplyFunction");
    }

    public void setHelloReply(RespValue helloReply) {
        this.helloReply = helloReply;
    }

    public List<AuthAttempt> getAuthAttempts() {
        return Collections.unmodifiableList(authAttempts);
    }

    public List<String> getReceivedCommands() {
        return Collections.unmodifiableList(receivedCommands);
    }

    public List<List<RespValue>> getReceivedCommandFrames() {
        return Collections.unmodifiableList(receivedCommandFrames);
    }

    public List<ReceivedRequest> getRequests() {
        return Collections.unmodifiableList(requests);
    }

    public ReceivedRequest getLastRequest() {
        if (requests.isEmpty()) {
            return null;
        }
        return requests.get(requests.size() - 1);
    }

    /**
     * Handle a decoded RESP command (as an array of RespValues). Returns a RespValue response or null
     * for failure modes.
     */
    public RespValue handle(List<RespValue> command) {
        if (command.isEmpty()) {
            return new RespError("ERR", "empty command");
        }

        String cmdName = extractString(command.get(0)).toUpperCase(Locale.ROOT);
        receivedCommands.add(cmdName);
        receivedCommandFrames.add(Collections.unmodifiableList(new ArrayList<>(command)));

        // Check failure mode
        if (failureMode != FailureMode.NONE) {
            if (failureCommand == null || failureCommand.equalsIgnoreCase(cmdName)) {
                if (!failureAfterReply) {
                    return null; // signals hang/drop
                }
            }
        }

        RespValue response;
        switch (cmdName) {
            case "HELLO":
                response = handleHello(command);
                break;
            case "AUTH":
                response = handleAuth(command);
                break;
            case "AWS.CONFIG":
                response = handleConfig(command);
                break;
            case "AWS.REQUEST":
                response = handleRequest(command);
                break;
            default:
                response = new RespError("ERR", "unknown command: " + cmdName);
                break;
        }

        // Check failure after reply
        if (failureMode != FailureMode.NONE && failureAfterReply) {
            if (failureCommand == null || failureCommand.equalsIgnoreCase(cmdName)) {
                // Return response but signal failure
                return response;
            }
        }

        return response;
    }

    private RespValue handleAuth(List<RespValue> command) {
        if (command.size() != 3) {
            return new RespError("ERR", "AUTH requires exactly 2 arguments, got " + (command.size() - 1));
        }
        String userId = extractString(command.get(1));
        String token = extractString(command.get(2));
        authAttempts.add(new AuthAttempt(userId, token));
        return authReplyFunction.apply(userId, token);
    }

    private RespValue handleHello(List<RespValue> command) {
        if (command.size() < 2) {
            return new RespError("NOPROTO", "unsupported protocol version");
        }
        String protocol = extractString(command.get(1));
        if (!"2".equals(protocol) && !"3".equals(protocol)) {
            return new RespError("NOPROTO", "unsupported protocol version");
        }

        int trailerSize = command.size() - 2;
        if (trailerSize != 0
                && (trailerSize != 3 || !"AUTH".equalsIgnoreCase(extractString(command.get(2))))) {
            return new RespError("ERR", "syntax error");
        }
        if (trailerSize == 3) {
            String userId = extractString(command.get(3));
            String token = extractString(command.get(4));
            authAttempts.add(new AuthAttempt(userId, token));
            RespValue authReply = authReplyFunction.apply(userId, token);
            if (authReply instanceof RespError) {
                return authReply;
            }
        }
        if (helloReply != null) {
            return helloReply;
        }

        Map<RespValue, RespValue> fields = new LinkedHashMap<>();
        fields.put(bs("server"), bs("valkey"));
        fields.put(bs("version"), bs("8.0.0"));
        fields.put(bs("proto"), new RespInteger(Long.parseLong(protocol)));
        fields.put(bs("id"), new RespInteger(1));
        fields.put(bs("mode"), bs("cluster"));
        fields.put(bs("role"), bs("primary"));
        fields.put(bs("modules"), new RespArray(Collections.emptyList()));
        if ("2".equals(protocol)) {
            List<RespValue> flatFields = new ArrayList<>();
            for (Map.Entry<RespValue, RespValue> field : fields.entrySet()) {
                flatFields.add(field.getKey());
                flatFields.add(field.getValue());
            }
            return new RespArray(flatFields);
        }
        return new RespMap(fields);
    }

    public static final class AuthAttempt {
        private final String userId;
        private final String token;

        private AuthAttempt(String userId, String token) {
            this.userId = userId;
            this.token = token;
        }

        public String getUserId() {
            return userId;
        }

        public String getToken() {
            return token;
        }
    }

    private RespValue handleConfig(List<RespValue> command) {
        // Expects: aws.config [json_overrides]. Zero args means "overrides unchanged since
        // last send"; one arg is the explicit-overrides form. Two or more args is invalid.
        int argCount = command.size() - 1;
        if (argCount > 1) {
            return new RespError("ERR", "aws.config takes at most 1 argument, got " + argCount);
        }

        overridesSupplied = argCount == 1;
        if (argCount == 0) {
            if (noArgConfigError != null) {
                return noArgConfigError;
            }
            if (noArgConfigReply == null) {
                return RespNull.INSTANCE;
            }
            return new RespBulkString(noArgConfigReply.getBytes(StandardCharsets.UTF_8));
        }
        if (configError != null) {
            return configError;
        }
        return new RespBulkString(configJson.getBytes(StandardCharsets.UTF_8));
    }

    private RespValue handleRequest(List<RespValue> command) {
        // Expects: aws.request method url headers body metadata
        // Minimum 6 args (command + 5 mandatory)
        if (command.size() < 6) {
            return new RespError(
                    "ERR", "aws.request requires at least 5 arguments, got " + (command.size() - 1));
        }

        List<byte[]> rawArgs = new ArrayList<>();
        for (int i = 1; i < command.size(); i++) {
            rawArgs.add(extractBytes(command.get(i)));
        }

        String method = extractString(command.get(1));
        String url = extractString(command.get(2));
        String headers = extractString(command.get(3));
        byte[] body = extractBytes(command.get(4));
        String metadata = extractString(command.get(5));

        ReceivedRequest req = new ReceivedRequest(method, url, headers, body, metadata, rawArgs);
        requests.add(req);

        // Use reply function if set, otherwise use static reply
        Reply effectiveReply = replyFunction != null ? replyFunction.apply(req) : reply;

        if (effectiveReply.hasRequestId()) {
            return new RespSimpleString(
                    Long.toString(effectiveReply.getRequestId()).getBytes(StandardCharsets.UTF_8));
        }
        if (effectiveReply.isError()) {
            return new RespError(effectiveReply.getErrorCode(), effectiveReply.getErrorMessage());
        }

        // Build response array: [status, headers_str, body]
        List<RespValue> responseElements = new ArrayList<>();
        responseElements.add(new RespInteger(effectiveReply.getStatusCode()));

        // Format response headers
        StringBuilder headerStr = new StringBuilder();
        if (effectiveReply.getHeaders() != null && !effectiveReply.getHeaders().isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : effectiveReply.getHeaders().entrySet()) {
                if (!first) {
                    headerStr.append('\n');
                }
                headerStr.append(entry.getKey()).append(':').append(entry.getValue());
                first = false;
            }
        }
        responseElements.add(new RespBulkString(headerStr.toString().getBytes(StandardCharsets.UTF_8)));
        responseElements.add(
                new RespBulkString(
                        effectiveReply.getBody() != null ? effectiveReply.getBody() : new byte[0]));

        return new RespArray(responseElements);
    }

    private String extractString(RespValue value) {
        if (value instanceof RespBulkString) {
            return new String(((RespBulkString) value).getValue(), StandardCharsets.UTF_8);
        } else if (value instanceof RespSimpleString) {
            return new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8);
        } else {
            throw new ElastiCacheIntegrationsProtocolError("Expected string value, got: " + value);
        }
    }

    private byte[] extractBytes(RespValue value) {
        if (value instanceof RespBulkString) {
            return ((RespBulkString) value).getValue();
        } else if (value instanceof RespSimpleString) {
            return ((RespSimpleString) value).getValue();
        } else {
            throw new ElastiCacheIntegrationsProtocolError("Expected bytes value, got: " + value);
        }
    }

    private RespBulkString bs(String value) {
        return new RespBulkString(value.getBytes(StandardCharsets.UTF_8));
    }
}
