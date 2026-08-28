package software.amazon.photon.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.photon.exception.PhotonProtocolError;
import software.amazon.photon.resp.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MockCacheTest {

    private MockCache cache;

    @BeforeEach
    void setUp() {
        cache = new MockCache();
    }

    // === Request parsing ===

    @Test
    void handleRequest_basic() {
        List<RespValue> command = makeRequestCommand("GET", "https://example.com",
                "host:example.com", new byte[0], "service=s3&op=GetObject");

        RespValue response = cache.handle(command);
        assertNotNull(response);
        assertTrue(response instanceof RespArray);

        ReceivedRequest req = cache.getLastRequest();
        assertNotNull(req);
        assertEquals("GET", req.getMethod());
        assertEquals("https://example.com", req.getUrl());
        assertEquals("host:example.com", req.getHeaders());
        assertEquals("service=s3&op=GetObject", req.getMetadata());
        assertNull(req.getTtl());
        assertNull(req.getNttl());
    }

    @Test
    void handleRequest_withTtl() {
        List<RespValue> command = makeRequestCommand("GET", "https://example.com",
                "", new byte[0], "service=s3&op=Get",
                "TTL", "300");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertEquals("300", req.getTtl());
        assertNull(req.getNttl());
    }

    @Test
    void handleRequest_withNttl() {
        List<RespValue> command = makeRequestCommand("GET", "https://example.com",
                "", new byte[0], "service=s3&op=Get",
                "NTTL", "60");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertNull(req.getTtl());
        assertEquals("60", req.getNttl());
    }

    @Test
    void handleRequest_withBothTtlAndNttl() {
        List<RespValue> command = makeRequestCommand("POST", "https://example.com/path",
                "content-type:application/json", "body".getBytes(StandardCharsets.UTF_8),
                "service=ec2&op=Describe",
                "TTL", "300", "NTTL", "60");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("300", req.getTtl());
        assertEquals("60", req.getNttl());
    }

    @Test
    void handleRequest_tooFewArgs() {
        // Only 3 args instead of 5 mandatory
        List<RespValue> command = Arrays.asList(
                bs("photon.request"),
                bs("GET"),
                bs("https://example.com"),
                bs("headers")
        );

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
        RespError error = (RespError) response;
        assertEquals("ERR", error.getCode());
        assertTrue(error.getMessage().contains("at least 5 arguments"));
    }

    // === Config handling ===

    @Test
    void handleConfig_valid() {
        List<RespValue> command = Arrays.asList(
                bs("photon.config"),
                bs("{}")
        );
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespBulkString);
    }

    @Test
    void handleConfig_wrongArgCount() {
        // No args
        List<RespValue> command = Arrays.asList(bs("photon.config"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
        RespError error = (RespError) response;
        assertTrue(error.getMessage().contains("exactly 1 argument"));
    }

    @Test
    void handleConfig_tooManyArgs() {
        List<RespValue> command = Arrays.asList(
                bs("photon.config"),
                bs("{}"),
                bs("extra")
        );
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
    }

    // === AUTH ===

    @Test
    void handleAuth() {
        List<RespValue> command = Arrays.asList(bs("AUTH"), bs("password123"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespSimpleString);
        assertArrayEquals("OK".getBytes(), ((RespSimpleString) response).getValue());
    }

    // === Reply configuration ===

    @Test
    void handleRequest_customReply() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "text/plain");
        cache.setReply(Reply.ok(201, headers, "created".getBytes(StandardCharsets.UTF_8)));

        List<RespValue> command = makeRequestCommand("POST", "https://example.com",
                "", new byte[0], "service=s3&op=Put");

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespArray);
        RespArray array = (RespArray) response;
        assertEquals(3, array.getElements().size());
        assertEquals(201, ((RespInteger) array.getElements().get(0)).getValue());
    }

    @Test
    void handleRequest_errorReply() {
        cache.setReply(Reply.error("CACHE", "miss"));

        List<RespValue> command = makeRequestCommand("GET", "https://example.com",
                "", new byte[0], "service=s3&op=Get");

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
        RespError error = (RespError) response;
        assertEquals("CACHE", error.getCode());
        assertEquals("miss", error.getMessage());
    }

    @Test
    void handleRequest_requestIdReply() {
        cache.setReply(Reply.requestId(Long.MAX_VALUE));

        RespValue response = cache.handle(makeRequestCommand(
                "GET", "https://example.com", "", new byte[0], "service=s3&op=Get"));

        assertEquals(new RespSimpleString(
                Long.toString(Long.MAX_VALUE).getBytes(StandardCharsets.UTF_8)), response);
    }

    // === Recording ===

    @Test
    void recordsCommands() {
        cache.handle(Arrays.asList(bs("AUTH"), bs("pass")));
        cache.handle(Arrays.asList(bs("photon.config"), bs("{}")));

        List<String> commands = cache.getReceivedCommands();
        assertEquals(2, commands.size());
        assertEquals("AUTH", commands.get(0));
        assertEquals("PHOTON.CONFIG", commands.get(1));
    }

    // === Failure modes ===

    @Test
    void failureMode_drop_returnsNull() {
        cache.setFailureMode(MockCache.FailureMode.DROP);

        List<RespValue> command = Arrays.asList(bs("AUTH"), bs("pass"));
        RespValue response = cache.handle(command);
        assertNull(response);
    }

    @Test
    void failureMode_specificCommand() {
        cache.setFailureMode(MockCache.FailureMode.DROP);
        cache.setFailureCommand("photon.request");

        // AUTH should still work
        List<RespValue> authCmd = Arrays.asList(bs("AUTH"), bs("pass"));
        RespValue authResp = cache.handle(authCmd);
        assertNotNull(authResp);

        // photon.request should fail
        List<RespValue> reqCmd = makeRequestCommand("GET", "https://example.com",
                "", new byte[0], "service=s3&op=Get");
        RespValue reqResp = cache.handle(reqCmd);
        assertNull(reqResp);
    }

    // === Helpers ===

    private List<RespValue> makeRequestCommand(String method, String url, String headers,
                                                byte[] body, String metadata, String... kwargs) {
        List<RespValue> command = new ArrayList<>();
        command.add(bs("photon.request"));
        command.add(bs(method));
        command.add(bs(url));
        command.add(bs(headers));
        command.add(new RespBulkString(body));
        command.add(bs(metadata));
        for (String kwarg : kwargs) {
            command.add(bs(kwarg));
        }
        return command;
    }

    private RespBulkString bs(String s) {
        return new RespBulkString(s.getBytes(StandardCharsets.UTF_8));
    }
}
