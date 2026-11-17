// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** An intercepted AWS SDK request to be forwarded to the cache. */
public final class ElastiCacheIntegrationsRequest {

    private final String method;
    private final String url;
    private final Map<String, String> headers;
    private final byte[] body;
    private final RequestMetadata metadata;

    /**
     * Create a request.
     *
     * @param method the HTTP method
     * @param url the request URL
     * @param headers the request headers, or {@code null} for none
     * @param body the request body, or {@code null} for an empty body
     * @param metadata the originating request metadata, or {@code null} for none
     * @throws NullPointerException if {@code method} or {@code url} is null
     */
    public ElastiCacheIntegrationsRequest(
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

    /**
     * Create a request with an empty body.
     *
     * @param method the HTTP method
     * @param url the request URL
     * @param headers the request headers, or {@code null} for none
     * @param metadata the originating request metadata, or {@code null} for none
     * @throws NullPointerException if {@code method} or {@code url} is null
     */
    public ElastiCacheIntegrationsRequest(
            String method, String url, Map<String, String> headers, RequestMetadata metadata) {
        this(method, url, headers, new byte[0], metadata);
    }

    /**
     * Return the HTTP method.
     *
     * @return the HTTP method
     */
    public String getMethod() {
        return method;
    }

    /**
     * Return the request URL.
     *
     * @return the request URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Return the request headers.
     *
     * @return the request headers, empty when none were given
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Return the request body.
     *
     * @return the request body, empty when none was given
     */
    public byte[] getBody() {
        return body;
    }

    /**
     * Return the originating request metadata.
     *
     * @return the originating request metadata
     */
    public RequestMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ElastiCacheIntegrationsRequest)) return false;
        ElastiCacheIntegrationsRequest that = (ElastiCacheIntegrationsRequest) o;
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
        return "ElastiCacheIntegrationsRequest{method='" + method + "', url='" + url + "'}";
    }
}
