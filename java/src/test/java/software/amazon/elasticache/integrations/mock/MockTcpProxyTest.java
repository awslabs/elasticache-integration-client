package software.amazon.elasticache.integrations.mock;

import static org.junit.jupiter.api.Assertions.*;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class MockTcpProxyTest {

    @Test
    void anotherLoopbackListenerCannotShareItsPort() throws Exception {
        // Tests connect to "localhost". If another process could listen on 127.0.0.1 with the
        // same port, the client would reach that process instead of this proxy (issue #97).
        MockTcpProxy proxy = new MockTcpProxy("localhost", 1);
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            InetSocketAddress samePort =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.getPort());
            assertThrows(BindException.class, () -> other.bind(samePort));
        } finally {
            proxy.close();
        }
    }
}
