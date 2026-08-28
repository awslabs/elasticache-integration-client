package software.amazon.photon.mock;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Represents a canned reply from the mock cache server.
 */
public class Reply {

    private final boolean isError;
    private final int statusCode;
    private final Map<String, String> headers;
    private final byte[] body;
    private final String errorCode;
    private final String errorMessage;
    private final Long requestId;

    private Reply(boolean isError, int statusCode, Map<String, String> headers, byte[] body,
                  String errorCode, String errorMessage, Long requestId) {
        this.isError = isError;
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.requestId = requestId;
    }

    public static Reply ok(int statusCode, Map<String, String> headers, byte[] body) {
        return new Reply(false, statusCode, headers, body, null, null, null);
    }

    public static Reply ok(int statusCode, Map<String, String> headers, String body) {
        return ok(statusCode, headers, body.getBytes(StandardCharsets.UTF_8));
    }

    public static Reply error(String code, String message) {
        return new Reply(true, 0, null, null, code, message, null);
    }

    public static Reply requestId(long requestId) {
        return new Reply(false, 0, null, null, null, null, requestId);
    }

    public boolean isError() {
        return isError;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean hasRequestId() {
        return requestId != null;
    }

    public long getRequestId() {
        return requestId;
    }
}
