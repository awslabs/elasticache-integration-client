package software.amazon.elasticache.integrations.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsError;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;
import software.amazon.elasticache.integrations.model.RequestMetadata;

/**
 * Adapts between AWS SDK v2 HTTP types and Cache request/response models.
 *
 * <p>Used by {@link ElastiCacheIntegrationsInterceptor} to convert SDK requests before sending to
 * the cache, and to convert Cache responses back to SDK responses.
 */
public final class SdkAdapters {

    private SdkAdapters() {}

    /**
     * Convert an AWS SDK HTTP request to a Cache request.
     *
     * @param sdkRequest the SDK request before signing
     * @param executionAttributes the execution attributes (for service/operation metadata)
     * @return the Cache request
     */
    public static ElastiCacheIntegrationsRequest toElastiCacheIntegrationsRequest(
            SdkHttpFullRequest sdkRequest, ExecutionAttributes executionAttributes) {
        return toElastiCacheIntegrationsRequest(
                sdkRequest, executionAttributes, extractBody(sdkRequest));
    }

    static ElastiCacheIntegrationsRequest toElastiCacheIntegrationsRequest(
            SdkHttpFullRequest sdkRequest, ExecutionAttributes executionAttributes, byte[] body) {
        // Extract method and URL
        String method = sdkRequest.method().name();
        String url = sdkRequest.getUri().toString();

        // Extract headers (flatten multi-value headers)
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : sdkRequest.headers().entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (!values.isEmpty()) {
                // Join multiple values with comma (per HTTP spec)
                headers.put(key, String.join(", ", values));
            }
        }

        // Extract metadata from execution attributes
        RequestMetadata metadata = extractMetadata(executionAttributes);

        return new ElastiCacheIntegrationsRequest(method, url, headers, body, metadata);
    }

    /**
     * Convert a Cache response to an AWS SDK HTTP response.
     *
     * @param cacheResponse the Cache response
     * @return the SDK response
     */
    public static SdkHttpFullResponse toSdkResponse(ElastiCacheIntegrationsResponse cacheResponse) {
        SdkHttpFullResponse.Builder builder =
                SdkHttpFullResponse.builder().statusCode(cacheResponse.getStatusCode());

        // Convert headers
        if (cacheResponse.getHeaders() != null) {
            for (Map.Entry<String, String> entry : cacheResponse.getHeaders().entrySet()) {
                builder.putHeader(entry.getKey(), entry.getValue());
            }
        }

        // Set body as AbortableInputStream
        byte[] body = cacheResponse.getBody();
        if (body != null && body.length > 0) {
            builder.content(AbortableInputStream.create(new ByteArrayInputStream(body)));
        }

        return builder.build();
    }

    /** Extract the request body as a byte array. */
    private static byte[] extractBody(SdkHttpFullRequest request) {
        Optional<? extends InputStream> contentStreamProvider =
                request
                        .contentStreamProvider()
                        .map(
                                provider -> {
                                    try {
                                        return provider.newStream();
                                    } catch (Exception e) {
                                        throw new ElastiCacheIntegrationsError("Failed to read request body", e);
                                    }
                                });

        if (!contentStreamProvider.isPresent()) {
            return new byte[0];
        }

        try (InputStream stream = contentStreamProvider.get()) {
            return readAllBytes(stream);
        } catch (IOException e) {
            throw new ElastiCacheIntegrationsError("Failed to read request body", e);
        }
    }

    /** Read all bytes from an input stream. */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    /** Extract service and operation metadata from execution attributes. */
    private static RequestMetadata extractMetadata(ExecutionAttributes attributes) {
        String service = null;
        String operation = null;

        if (attributes != null) {
            // Service name
            service = attributes.getAttribute(SdkExecutionAttribute.SERVICE_NAME);

            // Operation name
            operation = attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
        }

        return new RequestMetadata(service, operation);
    }
}
