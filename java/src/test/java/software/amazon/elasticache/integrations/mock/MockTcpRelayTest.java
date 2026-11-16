package software.amazon.elasticache.integrations.mock;

import static org.junit.jupiter.api.Assertions.*;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class MockTcpRelayTest {

    @Test
    void anotherLoopbackListenerCannotShareItsPort() throws Exception {
        // Tests connect to "localhost". If another process could listen on 127.0.0.1 with the
        // same port, the client would reach that process instead of this relay (issue #97).
        MockTcpRelay relay = new MockTcpRelay("localhost", 1);
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            InetSocketAddress samePort =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getPort());
            assertThrows(BindException.class, () -> other.bind(samePort));
        } finally {
            relay.close();
        }
    }
}
