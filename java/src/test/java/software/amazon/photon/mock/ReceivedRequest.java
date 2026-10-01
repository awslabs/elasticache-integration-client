package software.amazon.photon.mock;

import java.util.List;

/** Represents a request as received and parsed by the mock cache. */
public class ReceivedRequest {

    private final String method;
    private final String url;
    private final String headers;
    private final byte[] body;
    private final String metadata;
    private final String ttl;
    private final String nttl;
    private final List<byte[]> rawArgs;

    public ReceivedRequest(
            String method,
            String url,
            String headers,
            byte[] body,
            String metadata,
            String ttl,
            String nttl,
            List<byte[]> rawArgs) {
        this.method = method;
        this.url = url;
        this.headers = headers;
        this.body = body;
        this.metadata = metadata;
        this.ttl = ttl;
        this.nttl = nttl;
        this.rawArgs = rawArgs;
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getTtl() {
        return ttl;
    }

    public String getNttl() {
        return nttl;
    }

    public List<byte[]> getRawArgs() {
        return rawArgs;
    }

    @Override
    public String toString() {
        return "ReceivedRequest{method='" + method + "', url='" + url + "'}";
    }
}
