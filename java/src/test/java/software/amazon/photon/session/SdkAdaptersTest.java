package software.amazon.photon.session;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.ContentStreamProvider;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SdkAdapters.
 */
class SdkAdaptersTest {

    // === toPhotonRequest tests ===

    @Test
    void toPhotonRequest_extractsMethodAndUrl() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertEquals("POST", photonRequest.getMethod());
        assertEquals("https://dynamodb.us-east-1.amazonaws.com/", photonRequest.getUrl());
    }

    @Test
    void toPhotonRequest_extractsHeaders() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("https://example.com/"))
                .putHeader("Content-Type", "application/json")
                .putHeader("X-Custom-Header", "value")
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertEquals("application/json", photonRequest.getHeaders().get("Content-Type"));
        assertEquals("value", photonRequest.getHeaders().get("X-Custom-Header"));
    }

    @Test
    void toPhotonRequest_joinsMultiValueHeaders() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("https://example.com/"))
                .appendHeader("Accept", "text/html")
                .appendHeader("Accept", "application/json")
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertEquals("text/html, application/json", photonRequest.getHeaders().get("Accept"));
    }

    @Test
    void toPhotonRequest_extractsBody() {
        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://example.com/"))
                .contentStreamProvider(() -> new ByteArrayInputStream(body))
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertArrayEquals(body, photonRequest.getBody());
    }

    @Test
    void toPhotonRequest_emptyBodyWhenNoContent() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("https://example.com/"))
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertEquals(0, photonRequest.getBody().length);
    }

    @Test
    void toPhotonRequest_extractsMetadataFromExecutionAttributes() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                .build();

        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
        attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, attributes);

        assertEquals("dynamodb", photonRequest.getMetadata().getService());
        assertEquals("GetItem", photonRequest.getMetadata().getOperation());
    }

    @Test
    void toPhotonRequest_handlesNullExecutionAttributes() {
        SdkHttpFullRequest sdkRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .uri(URI.create("https://example.com/"))
                .build();

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(sdkRequest, null);

        assertNotNull(photonRequest.getMetadata());
        assertNull(photonRequest.getMetadata().getService());
        assertNull(photonRequest.getMetadata().getOperation());
    }

    // === toSdkResponse tests ===

    @Test
    void toSdkResponse_setsStatusCode() {
        Map<String, String> headers = new HashMap<>();
        PhotonResponse photonResponse = new PhotonResponse(200, headers, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        assertEquals(200, sdkResponse.statusCode());
    }

    @Test
    void toSdkResponse_setsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Request-Id", "12345");
        PhotonResponse photonResponse = new PhotonResponse(200, headers, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        assertTrue(sdkResponse.firstMatchingHeader("Content-Type").isPresent());
        assertEquals("application/json", sdkResponse.firstMatchingHeader("Content-Type").get());
        assertEquals("12345", sdkResponse.firstMatchingHeader("X-Request-Id").get());
    }

    @Test
    void toSdkResponse_setsBody() throws Exception {
        byte[] body = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        PhotonResponse photonResponse = new PhotonResponse(200, Collections.emptyMap(), body);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        assertTrue(sdkResponse.content().isPresent());
        byte[] responseBody = readAllBytes(sdkResponse.content().get());
        assertArrayEquals(body, responseBody);
    }

    @Test
    void toSdkResponse_handlesEmptyBody() {
        PhotonResponse photonResponse = new PhotonResponse(204, Collections.emptyMap(), new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        // Empty body should result in no content
        assertFalse(sdkResponse.content().isPresent());
    }

    @Test
    void toSdkResponse_handlesNullBody() {
        PhotonResponse photonResponse = new PhotonResponse(204, Collections.emptyMap(), null);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        assertFalse(sdkResponse.content().isPresent());
    }

    @Test
    void toSdkResponse_handlesNullHeaders() {
        PhotonResponse photonResponse = new PhotonResponse(200, null, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(photonResponse);

        assertEquals(200, sdkResponse.statusCode());
        assertTrue(sdkResponse.headers().isEmpty());
    }

    // === Round-trip tests ===

    @Test
    void roundTrip_preservesRequestData() {
        byte[] body = "{\"TableName\":\"Users\"}".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest originalRequest = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                .putHeader("Content-Type", "application/x-amz-json-1.0")
                .putHeader("X-Amz-Target", "DynamoDB_20120810.GetItem")
                .contentStreamProvider(() -> new ByteArrayInputStream(body))
                .build();

        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
        attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");

        PhotonRequest photonRequest = SdkAdapters.toPhotonRequest(originalRequest, attributes);

        assertEquals("POST", photonRequest.getMethod());
        assertEquals("https://dynamodb.us-east-1.amazonaws.com/", photonRequest.getUrl());
        assertEquals("application/x-amz-json-1.0", photonRequest.getHeaders().get("Content-Type"));
        assertArrayEquals(body, photonRequest.getBody());
        assertEquals("dynamodb", photonRequest.getMetadata().getService());
        assertEquals("GetItem", photonRequest.getMetadata().getOperation());
    }

    private static byte[] readAllBytes(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;
        while ((bytesRead = stream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
