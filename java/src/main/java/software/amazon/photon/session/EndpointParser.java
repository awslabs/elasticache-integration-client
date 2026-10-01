package software.amazon.photon.session;

import software.amazon.photon.exception.PhotonError;

/**
 * Parses host:port endpoint strings.
 *
 * <p>Supports IPv6 addresses in bracket notation (e.g., {@code [::1]:6379}).
 */
public final class EndpointParser {

    private EndpointParser() {
        // Utility class
    }

    /**
     * Parse a host:port endpoint string.
     *
     * <p>Examples:
     *
     * <ul>
     *   <li>{@code cache.example.com:6379} → host=cache.example.com, port=6379
     *   <li>{@code 127.0.0.1:6379} → host=127.0.0.1, port=6379
     *   <li>{@code [::1]:6379} → host=::1, port=6379
     *   <li>{@code ::1:6379} → host=::1, port=6379 (unbracketed IPv6)
     * </ul>
     *
     * @param endpoint the endpoint string in host:port format
     * @return a ParsedEndpoint containing the host and port
     * @throws PhotonError if the endpoint is invalid
     */
    public static ParsedEndpoint parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new PhotonError("Invalid endpoint: endpoint cannot be null or empty");
        }

        // Find the last colon to split host:port
        int lastColon = endpoint.lastIndexOf(':');
        if (lastColon < 1 || lastColon == endpoint.length() - 1) {
            throw new PhotonError("Invalid endpoint \"" + endpoint + "\": expected host:port");
        }

        String hostPart = endpoint.substring(0, lastColon);
        String portPart = endpoint.substring(lastColon + 1);

        // Strip brackets from IPv6 addresses
        String host;
        if (hostPart.startsWith("[") && hostPart.endsWith("]")) {
            host = hostPart.substring(1, hostPart.length() - 1);
        } else {
            host = hostPart;
        }

        if (host.isEmpty()) {
            throw new PhotonError("Invalid endpoint \"" + endpoint + "\": host cannot be empty");
        }

        // Parse port
        int port;
        try {
            port = Integer.parseInt(portPart.trim());
        } catch (NumberFormatException e) {
            throw new PhotonError("Invalid port in endpoint \"" + endpoint + "\"");
        }

        if (port < 1 || port > 65535) {
            throw new PhotonError("Port out of range in endpoint \"" + endpoint + "\": " + port);
        }

        return new ParsedEndpoint(host, port);
    }

    /** A parsed host:port endpoint. */
    public static final class ParsedEndpoint {
        private final String host;
        private final int port;

        public ParsedEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ParsedEndpoint that = (ParsedEndpoint) obj;
            return port == that.port && host.equals(that.host);
        }

        @Override
        public int hashCode() {
            return 31 * host.hashCode() + port;
        }
    }
}
