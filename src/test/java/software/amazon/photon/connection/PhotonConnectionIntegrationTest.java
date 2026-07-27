package software.amazon.photon.connection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.ConnectionLost;
import software.amazon.photon.exception.PhotonConnectionError;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.ReceivedRequest;
import software.amazon.photon.mock.Reply;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;
import software.amazon.photon.model.ResolvedConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PhotonConnection against MockTlsServer.
 * Exercises the full flow: TLS connect → AUTH → photon.config → photon.request → response.
 */
class PhotonConnectionIntegrationTest {

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        server = new MockTlsServer(cache);
        // Trust the mock server's self-signed cert
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    // === Connection + Handshake ===

    @Test
    void connect_successfulHandshake() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            ResolvedConfig config = conn.connect("my-auth-token");
            assertNotNull(config);
            assertEquals("1.0", config.getVersion());
            assertEquals(Integer.valueOf(60), config.getRefreshInterval());
            assertNotNull(config.getRaw());
            assertTrue(conn.isConnected());
        } finally {
            conn.close();
        }
    }

    @Test
    void connect_recordsAuthAndConfig() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("secret");
            // The mock should have received AUTH and photon.config
            assertTrue(cache.getReceivedCommands().size() >= 2);
            assertEquals("AUTH", cache.getReceivedCommands().get(0));
            assertEquals("PHOTON.CONFIG", cache.getReceivedCommands().get(1));
        } finally {
            conn.close();
        }
    }

    @Test
    void connect_withConfigOverrides() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            Map<String, Object> overrides = new HashMap<>();
            overrides.put("refresh_interval", 120);
            ResolvedConfig config = conn.connect("token", overrides);
            assertNotNull(config);
        } finally {
            conn.close();
        }
    }

    // === photon.request ===

    @Test
    void request_basicGetItem() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/x-amz-json-1.0");
        cache.setReply(Reply.ok(200, respHeaders,
                "{\"Item\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/x-amz-json-1.0");
            headers.put("host", "dynamodb.us-east-1.amazonaws.com");

            PhotonRequest request = new PhotonRequest(
                    "POST",
                    "https://dynamodb.us-east-1.amazonaws.com/",
                    headers,
                    "{\"TableName\":\"Users\",\"Key\":{\"id\":{\"S\":\"123\"}}}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem")
            );

            PhotonResponse response = conn.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
            assertTrue(response.getBody().length > 0);
        } finally {
            conn.close();
        }
    }

    @Test
    void request_serverReceivesCorrectArgs() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Host", "example.com");
            headers.put("Content-Type", "application/json");
            // These should be excluded
            headers.put("Authorization", "Bearer secret");
            headers.put("X-Amz-Date", "20260723T000000Z");

            PhotonRequest request = new PhotonRequest(
                    "GET",
                    "https://example.com/resource",
                    headers,
                    new byte[0],
                    new RequestMetadata("s3", "GetObject")
            );

            conn.request(request);

            ReceivedRequest received = cache.getLastRequest();
            assertNotNull(received);
            assertEquals("GET", received.getMethod());
            assertEquals("https://example.com/resource", received.getUrl());
            assertEquals("service=s3&op=GetObject", received.getMetadata());

            // Verify excluded headers are not in the normalized form
            String normalizedHeaders = received.getHeaders();
            assertFalse(normalizedHeaders.contains("authorization"));
            assertFalse(normalizedHeaders.contains("x-amz-date"));
            // Included headers should be there (lowercased, sorted)
            assertTrue(normalizedHeaders.contains("content-type:application/json"));
            assertTrue(normalizedHeaders.contains("host:example.com"));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_multipleRequestsOnSameConnection() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            for (int i = 0; i < 5; i++) {
                PhotonRequest request = new PhotonRequest(
                        "GET",
                        "https://example.com/item/" + i,
                        Collections.emptyMap(),
                        new byte[0],
                        new RequestMetadata("dynamodb", "GetItem")
                );

                PhotonResponse response = conn.request(request);
                assertEquals(200, response.getStatusCode());
            }

            // All 5 requests should be recorded (plus AUTH and config)
            assertEquals(5, cache.getRequests().size());
        } finally {
            conn.close();
        }
    }

    @Test
    void request_customResponseStatus() {
        Map<String, String> respHeaders = new LinkedHashMap<>();
        respHeaders.put("content-type", "application/json");
        cache.setReply(Reply.ok(404, respHeaders, "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)));

        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/missing",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            PhotonResponse response = conn.request(request);
            assertEquals(404, response.getStatusCode());
            assertEquals("{\"error\":\"not found\"}", new String(response.getBody(), StandardCharsets.UTF_8));
        } finally {
            conn.close();
        }
    }

    @Test
    void request_errorReplyThrows() {
        cache.setReply(Reply.error("CACHE_MISS", "item not cached"));

        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/item",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            assertThrows(PhotonProtocolError.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    // === Connection state ===

    @Test
    void close_marksConnectionClosed() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        conn.connect("token");
        assertTrue(conn.isConnected());

        conn.close();
        assertFalse(conn.isConnected());
    }

    @Test
    void request_afterClose_throwsConnectionLost() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        conn.connect("token");
        conn.close();

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/item",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        assertThrows(ConnectionLost.class, () -> conn.request(request));
    }

    @Test
    void connect_invalidHost_throwsConnectionError() {
        // Use a non-routable address to trigger connection failure
        PhotonConnection conn = new PhotonConnection("192.0.2.1", 9999, clientSslContext,
                200, 200);
        assertThrows(PhotonConnectionError.class, () -> conn.connect("token"));
    }

    // === Drop failure mode ===

    @Test
    void request_serverDropsConnection_throwsConnectionLost() {
        cache.setFailureMode(MockCache.FailureMode.DROP);
        cache.setFailureCommand("photon.request");

        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            conn.connect("token");

            PhotonRequest request = new PhotonRequest(
                    "GET", "https://example.com/item",
                    Collections.emptyMap(), new byte[0],
                    new RequestMetadata("dynamodb", "GetItem")
            );

            assertThrows(ConnectionLost.class, () -> conn.request(request));
        } finally {
            conn.close();
        }
    }

    @Test
    void getConfig_returnsHandshakeConfig() {
        PhotonConnection conn = new PhotonConnection("localhost", server.getPort(), clientSslContext);
        try {
            ResolvedConfig config = conn.connect("token");
            assertEquals(config, conn.getConfig());
        } finally {
            conn.close();
        }
    }
}
