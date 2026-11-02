package software.amazon.elasticache.integrations.mock;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.elasticache.integrations.resp.*;

class MockCacheTest {

    private MockCache cache;

    @BeforeEach
    void setUp() {
        cache = new MockCache();
    }

    // === Request parsing ===

    @Test
    void handleRequest_basic() {
        List<RespValue> command =
                makeRequestCommand(
                        "GET",
                        "https://example.com",
                        "host:example.com",
                        new byte[0],
                        "service=s3&op=GetObject");

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
        List<RespValue> command =
                makeRequestCommand(
                        "GET", "https://example.com", "", new byte[0], "service=s3&op=Get", "TTL", "300");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertEquals("300", req.getTtl());
        assertNull(req.getNttl());
    }

    @Test
    void handleRequest_withNttl() {
        List<RespValue> command =
                makeRequestCommand(
                        "GET", "https://example.com", "", new byte[0], "service=s3&op=Get", "NTTL", "60");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertNull(req.getTtl());
        assertEquals("60", req.getNttl());
    }

    @Test
    void handleRequest_withBothTtlAndNttl() {
        List<RespValue> command =
                makeRequestCommand(
                        "POST",
                        "https://example.com/path",
                        "content-type:application/json",
                        "body".getBytes(StandardCharsets.UTF_8),
                        "service=ec2&op=Describe",
                        "TTL",
                        "300",
                        "NTTL",
                        "60");

        cache.handle(command);
        ReceivedRequest req = cache.getLastRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("300", req.getTtl());
        assertEquals("60", req.getNttl());
    }

    @Test
    void handleRequest_tooFewArgs() {
        // Only 3 args instead of 5 mandatory
        List<RespValue> command =
                Arrays.asList(bs("aws.request"), bs("GET"), bs("https://example.com"), bs("headers"));

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
        RespError error = (RespError) response;
        assertEquals("ERR", error.getCode());
        assertTrue(error.getMessage().contains("at least 5 arguments"));
    }

    // === Config handling ===

    @Test
    void handleConfig_valid() {
        List<RespValue> command = Arrays.asList(bs("aws.config"), bs("{}"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespBulkString);
    }

    @Test
    void handleConfig_noArgs_repliesRespNullByDefault() {
        // The zero-argument form ("overrides unchanged") is valid and replies with RESP3 null
        // unless a no-arg reply has been configured.
        List<RespValue> command = Arrays.asList(bs("aws.config"));
        RespValue response = cache.handle(command);
        assertEquals(RespNull.INSTANCE, response);
        assertFalse(cache.wasOverridesSupplied());
    }

    @Test
    void handleConfig_noArgs_canReplyAResolvedConfig() {
        cache.setNoArgConfigReply("{\"version\":\"1.0\",\"refresh_interval\":30,\"predicates\":[]}");
        List<RespValue> command = Arrays.asList(bs("aws.config"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespBulkString);
        assertEquals(
                "{\"version\":\"1.0\",\"refresh_interval\":30,\"predicates\":[]}",
                new String(((RespBulkString) response).getValue(), StandardCharsets.UTF_8));
    }

    @Test
    void handleConfig_oneArg_recordsOverridesSupplied() {
        List<RespValue> command = Arrays.asList(bs("aws.config"), bs("{}"));
        cache.handle(command);
        assertTrue(cache.wasOverridesSupplied());
    }

    @Test
    void handleConfig_tooManyArgs() {
        List<RespValue> command = Arrays.asList(bs("aws.config"), bs("{}"), bs("extra"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
    }

    // === AUTH ===

    @Test
    void handleAuth() {
        List<RespValue> command = Arrays.asList(bs("AUTH"), bs("iam-user"), bs("password123"));
        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespSimpleString);
        assertArrayEquals("OK".getBytes(), ((RespSimpleString) response).getValue());
        assertEquals("iam-user", cache.getAuthAttempts().get(0).getUserId());
        assertEquals("password123", cache.getAuthAttempts().get(0).getToken());
    }

    @Test
    void handleAuth_wrongArgCount() {
        RespValue response = cache.handle(Arrays.asList(bs("AUTH"), bs("password123")));
        assertTrue(response instanceof RespError);
        assertTrue(((RespError) response).getMessage().contains("exactly 2 arguments"));
    }

    // === HELLO ===

    @Test
    void handleHello3_withInlineAuthReturnsMapAndRecordsCredentials() {
        RespValue response =
                cache.handle(
                        Arrays.asList(bs("HELLO"), bs("3"), bs("AUTH"), bs("iam-user"), bs("password123")));

        assertTrue(response instanceof RespMap);
        RespValue proto = ((RespMap) response).getEntries().get(bs("proto"));
        assertEquals(new RespInteger(3), proto);
        assertEquals("iam-user", cache.getAuthAttempts().get(0).getUserId());
        assertEquals("password123", cache.getAuthAttempts().get(0).getToken());
    }

    @Test
    void handleHello2_returnsResp2Array() {
        RespValue response = cache.handle(Arrays.asList(bs("HELLO"), bs("2")));

        assertEquals(
                new RespArray(
                        Arrays.asList(
                                bs("server"),
                                bs("valkey"),
                                bs("version"),
                                bs("8.0.0"),
                                bs("proto"),
                                new RespInteger(2),
                                bs("id"),
                                new RespInteger(1),
                                bs("mode"),
                                bs("cluster"),
                                bs("role"),
                                bs("primary"),
                                bs("modules"),
                                new RespArray(Collections.emptyList()))),
                response);
    }

    @Test
    void handleHello_rejectsUnsupportedProtocol() {
        RespValue response = cache.handle(Arrays.asList(bs("HELLO"), bs("4")));

        assertEquals("NOPROTO", ((RespError) response).getCode());
    }

    @Test
    void handleHello_rejectsMalformedAuthTrailer() {
        RespValue response =
                cache.handle(Arrays.asList(bs("HELLO"), bs("3"), bs("AUTH"), bs("iam-user")));

        assertEquals("ERR", ((RespError) response).getCode());
    }

    // === Reply configuration ===

    @Test
    void handleRequest_customReply() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "text/plain");
        cache.setReply(Reply.ok(201, headers, "created".getBytes(StandardCharsets.UTF_8)));

        List<RespValue> command =
                makeRequestCommand("POST", "https://example.com", "", new byte[0], "service=s3&op=Put");

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespArray);
        RespArray array = (RespArray) response;
        assertEquals(3, array.getElements().size());
        assertEquals(201, ((RespInteger) array.getElements().get(0)).getValue());
    }

    @Test
    void handleRequest_errorReply() {
        cache.setReply(Reply.error("CACHE", "miss"));

        List<RespValue> command =
                makeRequestCommand("GET", "https://example.com", "", new byte[0], "service=s3&op=Get");

        RespValue response = cache.handle(command);
        assertTrue(response instanceof RespError);
        RespError error = (RespError) response;
        assertEquals("CACHE", error.getCode());
        assertEquals("miss", error.getMessage());
    }

    @Test
    void handleRequest_requestIdReply() {
        cache.setReply(Reply.requestId(Long.MAX_VALUE));

        RespValue response =
                cache.handle(
                        makeRequestCommand("GET", "https://example.com", "", new byte[0], "service=s3&op=Get"));

        assertEquals(
                new RespSimpleString(Long.toString(Long.MAX_VALUE).getBytes(StandardCharsets.UTF_8)),
                response);
    }

    // === Recording ===

    @Test
    void recordsCommands() {
        cache.handle(Arrays.asList(bs("AUTH"), bs("pass")));
        cache.handle(Arrays.asList(bs("aws.config"), bs("{}")));

        List<String> commands = cache.getReceivedCommands();
        assertEquals(2, commands.size());
        assertEquals("AUTH", commands.get(0));
        assertEquals("AWS.CONFIG", commands.get(1));
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
        cache.setFailureCommand("aws.request");

        // AUTH should still work
        List<RespValue> authCmd = Arrays.asList(bs("AUTH"), bs("pass"));
        RespValue authResp = cache.handle(authCmd);
        assertNotNull(authResp);

        // aws.request should fail
        List<RespValue> reqCmd =
                makeRequestCommand("GET", "https://example.com", "", new byte[0], "service=s3&op=Get");
        RespValue reqResp = cache.handle(reqCmd);
        assertNull(reqResp);
    }

    // === Helpers ===

    private List<RespValue> makeRequestCommand(
            String method, String url, String headers, byte[] body, String metadata, String... kwargs) {
        List<RespValue> command = new ArrayList<>();
        command.add(bs("aws.request"));
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
