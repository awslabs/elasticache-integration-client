package software.amazon.photon.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Represents an HTTP request to be sent through Photon. */
public class PhotonRequest {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final byte[] body;
    private final RequestMetadata metadata;

    public PhotonRequest(
            String method,
            String url,
            Map<String, String> headers,
            byte[] body,
            RequestMetadata metadata) {
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.url = Objects.requireNonNull(url, "url must not be null");
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.body = body != null ? body : new byte[0];
        this.metadata = metadata != null ? metadata : new RequestMetadata(null, null);
    }

    public PhotonRequest(
            String method, String url, Map<String, String> headers, RequestMetadata metadata) {
        this(method, url, headers, new byte[0], metadata);
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public RequestMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PhotonRequest that = (PhotonRequest) o;
        return Objects.equals(method, that.method)
                && Objects.equals(url, that.url)
                && Objects.equals(headers, that.headers)
                && Arrays.equals(body, that.body)
                && Objects.equals(metadata, that.metadata);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(method, url, headers, metadata);
        result = 31 * result + Arrays.hashCode(body);
        return result;
    }

    @Override
    public String toString() {
        return "PhotonRequest{method='" + method + "', url='" + url + "'}";
    }
}
