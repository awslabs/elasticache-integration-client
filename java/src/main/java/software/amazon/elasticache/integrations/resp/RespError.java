package software.amazon.elasticache.integrations.resp;

import java.util.Objects;

/** RESP3 error value. */
public class RespError implements RespValue {

    private final String code;
    private final String message;

    public RespError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RespError respError = (RespError) o;
        return Objects.equals(code, respError.code) && Objects.equals(message, respError.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message);
    }

    @Override
    public String toString() {
        return "RespError{code='" + code + "', message='" + message + "'}";
    }
}
