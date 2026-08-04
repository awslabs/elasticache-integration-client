package software.amazon.photon.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.auth.AuthCredentials;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.mock.MockCache;
import software.amazon.photon.mock.MockTlsServer;
import software.amazon.photon.mock.Reply;
import software.amazon.photon.connection.PhotonConnection;
import software.amazon.photon.model.PhotonRequest;
import software.amazon.photon.model.PhotonResponse;
import software.amazon.photon.model.RequestMetadata;

import javax.net.ssl.SSLContext;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PhotonSession.
 */
class PhotonSessionTest {

    private static final String TEST_USER = "test-user";
    private static final String TEST_TOKEN = "test-token";

    private MockCache cache;
    private MockTlsServer server;
    private SSLContext clientSslContext;

    @BeforeEach
    void setUp() throws Exception {
        cache = new MockCache();
        server = new MockTlsServer(cache);
        clientSslContext = server.getSslContext();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private PhotonConnection createConnection() {
        return new PhotonConnection("localhost", server.getPort(), clientSslContext);
    }

    // === Lifecycle tests ===

    @Test
    void open_transitionsToOpenState() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertEquals(PhotonSession.State.CREATED, session.getState());

        session.open();
        assertEquals(PhotonSession.State.OPEN, session.getState());
        assertTrue(session.isOpen());

        session.close();
    }

    @Test
    void close_transitionsToClosedState() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        assertTrue(session.isOpen());

        session.close();
        assertEquals(PhotonSession.State.CLOSED, session.getState());
        assertFalse(session.isOpen());
    }

    @Test
    void close_isIdempotent() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        session.close();
        session.close(); // Should not throw
        session.close();

        assertEquals(PhotonSession.State.CLOSED, session.getState());
    }

    @Test
    void open_onOpenedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();

        PhotonError error = assertThrows(PhotonError.class, session::open);
        assertTrue(error.getMessage().contains("open"));

        session.close();
    }

    @Test
    void open_onClosedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        PhotonError error = assertThrows(PhotonError.class, session::open);
        assertTrue(error.getMessage().contains("closed"));
    }

    // === Request tests ===

    @Test
    void request_onOpenSession_succeeds() {
        cache.setReply(Reply.ok(200, Collections.singletonMap("content-type", "application/json"),
                "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));

        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        try {
            session.open();

            PhotonRequest request = new PhotonRequest(
                    "POST",
                    "https://dynamodb.us-east-1.amazonaws.com/",
                    Collections.singletonMap("content-type", "application/json"),
                    "{\"TableName\":\"Test\"}".getBytes(StandardCharsets.UTF_8),
                    new RequestMetadata("dynamodb", "GetItem")
            );

            PhotonResponse response = session.request(request);

            assertEquals(200, response.getStatusCode());
            assertNotNull(response.getBody());
        } finally {
            session.close();
        }
    }

    @Test
    void request_onCreatedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        PhotonError error = assertThrows(PhotonError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("created"));
    }

    @Test
    void request_onClosedSession_throws() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        session.open();
        session.close();

        PhotonRequest request = new PhotonRequest(
                "GET", "https://example.com/",
                Collections.emptyMap(), new byte[0],
                new RequestMetadata("dynamodb", "GetItem")
        );

        PhotonError error = assertThrows(PhotonError.class, () -> session.request(request));
        assertTrue(error.getMessage().contains("closed"));
    }

    // === Config tests ===

    @Test
    void getResolvedConfig_afterOpen_returnsConfig() {
        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN);
        assertNull(session.getResolvedConfig());

        session.open();
        assertNotNull(session.getResolvedConfig());
        assertEquals("1.0", session.getResolvedConfig().getVersion());

        session.close();
    }

    @Test
    void open_withConfigOverrides_sendsOverrides() {
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("refresh_interval", 120);

        PhotonSession session = new PhotonSession(createConnection(), TEST_USER, TEST_TOKEN, overrides);
        session.open();

        // Verify the config command was sent
        assertTrue(cache.getReceivedCommands().contains("PHOTON.CONFIG"));

        session.close();
    }

    // === Auth provider tests ===

    @Test
    void open_callsAuthProvider() {
        AtomicInteger callCount = new AtomicInteger(0);
        PhotonSession session = new PhotonSession(createConnection(), () -> {
            callCount.incrementAndGet();
            return new AuthCredentials(TEST_USER, "dynamic-token");
        });

        session.open();
        assertEquals(1, callCount.get());

        session.close();
    }

    @Test
    void open_closesOnAuthFailure() {
        PhotonSession session = new PhotonSession(createConnection(), () -> {
            throw new RuntimeException("Auth provider failed");
        });

        assertThrows(RuntimeException.class, session::open);
        assertEquals(PhotonSession.State.CLOSED, session.getState());
    }
}
