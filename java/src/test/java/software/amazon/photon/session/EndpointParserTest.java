package software.amazon.photon.session;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.photon.exception.PhotonError;
import software.amazon.photon.session.EndpointParser.ParsedEndpoint;

class EndpointParserTest {

    @ParameterizedTest
    @CsvSource({
        "cache.example.com:6379, cache.example.com, 6379",
        "127.0.0.1:6379, 127.0.0.1, 6379",
        "localhost:11211, localhost, 11211",
        "my-cache.serverless.use1.cache.amazonaws.com:6379,"
                + " my-cache.serverless.use1.cache.amazonaws.com, 6379",
        "[::1]:6379, ::1, 6379",
        "::1:6379, ::1, 6379",
        "[2001:db8::1]:8080, 2001:db8::1, 8080",
        "host:1, host, 1",
        "host:65535, host, 65535"
    })
    void parseEndpoint_validEndpoints_returnsHostAndPort(
            String endpoint, String expectedHost, int expectedPort) {
        ParsedEndpoint result = EndpointParser.parseEndpoint(endpoint);

        assertEquals(expectedHost, result.getHost());
        assertEquals(expectedPort, result.getPort());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "cache.example.com",
                ":6379",
                "host:",
                "host:abc",
                "host:0",
                "host:65536",
                "host:-1",
                "host:999999"
            })
    void parseEndpoint_invalidEndpoints_throwsPhotonError(String endpoint) {
        assertThrows(PhotonError.class, () -> EndpointParser.parseEndpoint(endpoint));
    }

    @Test
    void parseEndpoint_nullEndpoint_throwsPhotonError() {
        assertThrows(PhotonError.class, () -> EndpointParser.parseEndpoint(null));
    }

    @Test
    void parseEndpoint_emptyBrackets_throwsPhotonError() {
        // []:6379 has an empty host after stripping brackets
        assertThrows(PhotonError.class, () -> EndpointParser.parseEndpoint("[]:6379"));
    }

    @Test
    void parsedEndpoint_toString_returnsHostColonPort() {
        ParsedEndpoint result = EndpointParser.parseEndpoint("cache.example.com:6379");
        assertEquals("cache.example.com:6379", result.toString());
    }

    @Test
    void parsedEndpoint_equals_comparesHostAndPort() {
        ParsedEndpoint a = new ParsedEndpoint("host", 6379);
        ParsedEndpoint b = new ParsedEndpoint("host", 6379);
        ParsedEndpoint c = new ParsedEndpoint("host", 6380);
        ParsedEndpoint d = new ParsedEndpoint("other", 6379);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
        assertNotEquals(a, null);
        assertNotEquals(a, "not a ParsedEndpoint");
    }

    @Test
    void parseEndpoint_portWithWhitespace_isTrimmed() {
        // The port parser trims whitespace
        ParsedEndpoint result = EndpointParser.parseEndpoint("host: 6379 ");
        assertEquals(6379, result.getPort());
    }
}
