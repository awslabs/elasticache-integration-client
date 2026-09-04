package software.amazon.photon.mock;

import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.resp.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Mock Photon cache server logic. Implements the command handling contract.
 * This is a pure handler class - no networking.
 */
public class MockCache {

    public enum FailureMode {
        NONE,
        HANG,
        DROP
    }

    private Reply reply = Reply.ok(200, Collections.emptyMap(), new byte[0]);
    private Function<ReceivedRequest, Reply> replyFunction = null;
    private String configJson = "{\"version\":\"1.0\",\"refresh_interval\":60,"
            + "\"predicates\":[{\"expr\":\"service == 'dynamodb' && operation in ops\","
            + "\"vars\":{\"ops\":[\"GetItem\",\"BatchGetItem\",\"Query\",\"Scan\","
            + "\"PutItem\",\"UpdateItem\",\"DeleteItem\",\"BatchWriteItem\","
            + "\"TransactGetItems\",\"TransactWriteItems\"]}}]}";
    private FailureMode failureMode = FailureMode.NONE;
    private String failureCommand = null;
    private boolean failureAfterReply = false;

    private final List<String> receivedCommands = new ArrayList<>();
    private final List<ReceivedRequest> requests = new ArrayList<>();

    public void setReply(Reply reply) {
        this.reply = reply;
        this.replyFunction = null; // Clear function when setting static reply
    }

    /**
     * Set a function to generate replies based on the request.
     * This overrides the static reply.
     */
    public void setReplyFunction(Function<ReceivedRequest, Reply> replyFunction) {
        this.replyFunction = replyFunction;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
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

    public List<String> getReceivedCommands() {
        return Collections.unmodifiableList(receivedCommands);
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
     * Handle a decoded RESP command (as an array of RespValues).
     * Returns a RespValue response or null for failure modes.
     */
    public RespValue handle(List<RespValue> command) {
        if (command.isEmpty()) {
            return new RespError("ERR", "empty command");
        }

        String cmdName = extractString(command.get(0)).toUpperCase();
        receivedCommands.add(cmdName);

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
            case "AUTH":
                response = new RespSimpleString("OK".getBytes(StandardCharsets.UTF_8));
                break;
            case "PHOTON.CONFIG":
                response = handleConfig(command);
                break;
            case "PHOTON.REQUEST":
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

    private RespValue handleConfig(List<RespValue> command) {
        // Expects: photon.config [json_overrides]
        if (command.size() != 2) {
            return new RespError("ERR", "photon.config requires exactly 1 argument, got " + (command.size() - 1));
        }
        return new RespBulkString(configJson.getBytes(StandardCharsets.UTF_8));
    }

    private RespValue handleRequest(List<RespValue> command) {
        // Expects: photon.request method url headers body metadata [TTL value] [NTTL value]
        // Minimum 6 args (command + 5 mandatory)
        if (command.size() < 6) {
            return new RespError("ERR", "photon.request requires at least 5 arguments, got " + (command.size() - 1));
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

        // Parse optional keyword args (TTL, NTTL)
        String ttl = null;
        String nttl = null;
        int i = 6;
        while (i < command.size() - 1) {
            String keyword = extractString(command.get(i)).toUpperCase();
            String value = extractString(command.get(i + 1));
            if ("TTL".equals(keyword)) {
                ttl = value;
            } else if ("NTTL".equals(keyword)) {
                nttl = value;
            }
            i += 2;
        }

        ReceivedRequest req = new ReceivedRequest(method, url, headers, body, metadata, ttl, nttl, rawArgs);
        requests.add(req);

        // Use reply function if set, otherwise use static reply
        Reply effectiveReply = replyFunction != null ? replyFunction.apply(req) : reply;

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
        responseElements.add(new RespBulkString(effectiveReply.getBody() != null ? effectiveReply.getBody() : new byte[0]));

        return new RespArray(responseElements);
    }

    private String extractString(RespValue value) {
        if (value instanceof RespBulkString) {
            return new String(((RespBulkString) value).getValue(), StandardCharsets.UTF_8);
        } else if (value instanceof RespSimpleString) {
            return new String(((RespSimpleString) value).getValue(), StandardCharsets.UTF_8);
        } else {
            throw new PhotonProtocolError("Expected string value, got: " + value);
        }
    }

    private byte[] extractBytes(RespValue value) {
        if (value instanceof RespBulkString) {
            return ((RespBulkString) value).getValue();
        } else if (value instanceof RespSimpleString) {
            return ((RespSimpleString) value).getValue();
        } else {
            throw new PhotonProtocolError("Expected bytes value, got: " + value);
        }
    }
}
