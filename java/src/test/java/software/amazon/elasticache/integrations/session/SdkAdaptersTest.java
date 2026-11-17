// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpFullResponse;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsRequest;
import software.amazon.elasticache.integrations.model.ElastiCacheIntegrationsResponse;

/** Unit tests for SdkAdapters. */
class SdkAdaptersTest {

    // === toElastiCacheIntegrationsRequest tests ===

    @Test
    void toElastiCacheIntegrationsRequest_extractsMethodAndUrl() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertEquals("POST", cacheRequest.getMethod());
        assertEquals("https://dynamodb.us-east-1.amazonaws.com/", cacheRequest.getUrl());
    }

    @Test
    void toElastiCacheIntegrationsRequest_extractsHeaders() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .uri(URI.create("https://example.com/"))
                        .putHeader("Content-Type", "application/json")
                        .putHeader("X-Custom-Header", "value")
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertEquals("application/json", cacheRequest.getHeaders().get("Content-Type"));
        assertEquals("value", cacheRequest.getHeaders().get("X-Custom-Header"));
    }

    @Test
    void toElastiCacheIntegrationsRequest_joinsMultiValueHeaders() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .uri(URI.create("https://example.com/"))
                        .appendHeader("Accept", "text/html")
                        .appendHeader("Accept", "application/json")
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertEquals("text/html, application/json", cacheRequest.getHeaders().get("Accept"));
    }

    @Test
    void toElastiCacheIntegrationsRequest_extractsBody() {
        byte[] body = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://example.com/"))
                        .contentStreamProvider(() -> new ByteArrayInputStream(body))
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertArrayEquals(body, cacheRequest.getBody());
    }

    @Test
    void toElastiCacheIntegrationsRequest_emptyBodyWhenNoContent() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .uri(URI.create("https://example.com/"))
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertEquals(0, cacheRequest.getBody().length);
    }

    @Test
    void toElastiCacheIntegrationsRequest_extractsMetadataFromExecutionAttributes() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .build();

        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
        attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, attributes);

        assertEquals("dynamodb", cacheRequest.getMetadata().getService());
        assertEquals("GetItem", cacheRequest.getMetadata().getOperation());
    }

    @Test
    void toElastiCacheIntegrationsRequest_handlesNullExecutionAttributes() {
        SdkHttpFullRequest sdkRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.GET)
                        .uri(URI.create("https://example.com/"))
                        .build();

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(sdkRequest, null);

        assertNotNull(cacheRequest.getMetadata());
        assertNull(cacheRequest.getMetadata().getService());
        assertNull(cacheRequest.getMetadata().getOperation());
    }

    // === toSdkResponse tests ===

    @Test
    void toSdkResponse_setsStatusCode() {
        Map<String, String> headers = new HashMap<>();
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(200, headers, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        assertEquals(200, sdkResponse.statusCode());
    }

    @Test
    void toSdkResponse_setsHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Request-Id", "12345");
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(200, headers, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        assertTrue(sdkResponse.firstMatchingHeader("Content-Type").isPresent());
        assertEquals("application/json", sdkResponse.firstMatchingHeader("Content-Type").get());
        assertEquals("12345", sdkResponse.firstMatchingHeader("X-Request-Id").get());
    }

    @Test
    void toSdkResponse_setsBody() throws Exception {
        byte[] body = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(200, Collections.emptyMap(), body);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        assertTrue(sdkResponse.content().isPresent());
        byte[] responseBody = readAllBytes(sdkResponse.content().get());
        assertArrayEquals(body, responseBody);
    }

    @Test
    void toSdkResponse_handlesEmptyBody() {
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(204, Collections.emptyMap(), new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        // Empty body should result in no content
        assertFalse(sdkResponse.content().isPresent());
    }

    @Test
    void toSdkResponse_handlesNullBody() {
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(204, Collections.emptyMap(), null);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        assertFalse(sdkResponse.content().isPresent());
    }

    @Test
    void toSdkResponse_handlesNullHeaders() {
        ElastiCacheIntegrationsResponse cacheResponse =
                new ElastiCacheIntegrationsResponse(200, null, new byte[0]);

        SdkHttpFullResponse sdkResponse = SdkAdapters.toSdkResponse(cacheResponse);

        assertEquals(200, sdkResponse.statusCode());
        assertTrue(sdkResponse.headers().isEmpty());
    }

    // === Round-trip tests ===

    @Test
    void roundTrip_preservesRequestData() {
        byte[] body = "{\"TableName\":\"Users\"}".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest originalRequest =
                SdkHttpFullRequest.builder()
                        .method(SdkHttpMethod.POST)
                        .uri(URI.create("https://dynamodb.us-east-1.amazonaws.com/"))
                        .putHeader("Content-Type", "application/x-amz-json-1.0")
                        .putHeader("X-Amz-Target", "DynamoDB_20120810.GetItem")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body))
                        .build();

        ExecutionAttributes attributes = new ExecutionAttributes();
        attributes.putAttribute(SdkExecutionAttribute.SERVICE_NAME, "DynamoDb");
        attributes.putAttribute(SdkExecutionAttribute.OPERATION_NAME, "GetItem");

        ElastiCacheIntegrationsRequest cacheRequest =
                SdkAdapters.toElastiCacheIntegrationsRequest(originalRequest, attributes);

        assertEquals("POST", cacheRequest.getMethod());
        assertEquals("https://dynamodb.us-east-1.amazonaws.com/", cacheRequest.getUrl());
        assertEquals("application/x-amz-json-1.0", cacheRequest.getHeaders().get("Content-Type"));
        assertArrayEquals(body, cacheRequest.getBody());
        assertEquals("dynamodb", cacheRequest.getMetadata().getService());
        assertEquals("GetItem", cacheRequest.getMetadata().getOperation());
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
