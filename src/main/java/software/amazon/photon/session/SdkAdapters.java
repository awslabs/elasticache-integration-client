package software.amazon.photon.session;

import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adapts between AWS SDK v2 HTTP types and Photon request/response models.
 *
 * <p>Used by {@link PhotonInterceptor} to convert SDK requests before sending
 * to the cache, and to convert Photon responses back to SDK responses.
 */
public final class SdkAdapters {

    private SdkAdapters() {}

    /**
     * Convert an AWS SDK HTTP request to a Photon request.
     *
     * @param sdkRequest         the SDK request (already signed)
     * @param executionAttributes the execution attributes (for service/operation metadata)
     * @return the Photon request
     */
    public static PhotonRequest toPhotonRequest(SdkHttpFullRequest sdkRequest,
                                                  ExecutionAttributes executionAttributes) {
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

        // Extract body
        byte[] body = extractBody(sdkRequest);

        // Extract metadata from execution attributes
        RequestMetadata metadata = extractMetadata(executionAttributes);

        return new PhotonRequest(method, url, headers, body, metadata);
    }

    /**
     * Convert a Photon response to an AWS SDK HTTP response.
     *
     * @param photonResponse the Photon response
     * @return the SDK response
     */
    public static SdkHttpFullResponse toSdkResponse(PhotonResponse photonResponse) {
        SdkHttpFullResponse.Builder builder = SdkHttpFullResponse.builder()
                .statusCode(photonResponse.getStatusCode());

        // Convert headers
        if (photonResponse.getHeaders() != null) {
            for (Map.Entry<String, String> entry : photonResponse.getHeaders().entrySet()) {
                builder.putHeader(entry.getKey(), entry.getValue());
            }
        }

        // Set body as AbortableInputStream
        byte[] body = photonResponse.getBody();
        if (body != null && body.length > 0) {
            builder.content(AbortableInputStream.create(new ByteArrayInputStream(body)));
        }

        return builder.build();
    }

    /**
     * Extract the request body as a byte array.
     */
    private static byte[] extractBody(SdkHttpFullRequest request) {
        Optional<? extends InputStream> contentStreamProvider = request.contentStreamProvider()
                .map(provider -> {
                    try {
                        return provider.newStream();
                    } catch (Exception e) {
                        throw new PhotonError("Failed to read request body", e);
                    }
                });

        if (!contentStreamProvider.isPresent()) {
            return new byte[0];
        }

        try (InputStream stream = contentStreamProvider.get()) {
            return readAllBytes(stream);
        } catch (IOException e) {
            throw new PhotonError("Failed to read request body", e);
        }
    }

    /**
     * Read all bytes from an input stream.
     * (Java 11 compatible - InputStream.readAllBytes() is Java 9+)
     *
     * TODO: If Java 8 support is added, verify this implementation handles
     * all edge cases (large streams, empty streams, etc.)
     */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    /**
     * Extract service and operation metadata from execution attributes.
     */
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
