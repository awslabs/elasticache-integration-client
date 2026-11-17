// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.mock;

import static org.junit.jupiter.api.Assertions.*;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class MockTlsServerTest {

    @Test
    void anotherLoopbackListenerCannotShareItsPort() throws Exception {
        // Tests connect to "localhost". If another process could listen on 127.0.0.1 with the
        // same port, the client would reach that process instead of this server (issue #97).
        MockTlsServer server = new MockTlsServer(new MockCache());
        try (ServerSocket other = new ServerSocket()) {
            other.setReuseAddress(true);
            InetSocketAddress samePort =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort());
            assertThrows(BindException.class, () -> other.bind(samePort));
        } finally {
            server.close();
        }
    }
}
