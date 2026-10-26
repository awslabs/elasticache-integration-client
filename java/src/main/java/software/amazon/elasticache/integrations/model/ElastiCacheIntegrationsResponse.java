package software.amazon.elasticache.integrations.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Represents an HTTP response received through Cache. */
public class ElastiCacheIntegrationsResponse {

    private final int statusCode;
    private final Map<String, String> headers;
    private final byte[] body;

    public ElastiCacheIntegrationsResponse(int statusCode, Map<String, String> headers, byte[] body) {
        this.statusCode = statusCode;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.body = body != null ? body : new byte[0];
    }

    public ElastiCacheIntegrationsResponse(int statusCode, Map<String, String> headers) {
        this(statusCode, headers, new byte[0]);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ElastiCacheIntegrationsResponse that = (ElastiCacheIntegrationsResponse) o;
        return statusCode == that.statusCode
                && Objects.equals(headers, that.headers)
                && Arrays.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(statusCode, headers);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "ElastiCacheIntegrationsResponse{statusCode="
                + statusCode
                + ", headers="
                + headers
                + "}";
    }
}
