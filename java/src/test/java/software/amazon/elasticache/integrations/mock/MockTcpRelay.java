// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.mock;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Controllable TCP pass-through used to exercise TLS connection recovery. */
public final class MockTcpRelay implements Closeable {

    private final String targetHost;
    private final int targetPort;
    private final ServerSocket listener;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean forwarding = new AtomicBoolean(true);
    private final AtomicInteger acceptedConnections = new AtomicInteger();

    public MockTcpRelay(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        // Port 0 asks the OS for a free ephemeral port. Bind loopback, not the wildcard address,
        // so the port cannot be shared with another process listening on 127.0.0.1.
        this.listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        workers.execute(this::acceptLoop);
    }

    public int getPort() {
        return listener.getLocalPort();
    }

    public int getAcceptedConnections() {
        return acceptedConnections.get();
    }

    public void enableForwarding() {
        forwarding.set(true);
    }

    public void disableForwardingAndDisconnectClients() {
        forwarding.set(false);
        disconnectClients();
    }

    public void disconnectClients() {
        for (Socket socket : new ArrayList<>(sockets)) {
            closeQuietly(socket);
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = listener.accept();
                acceptedConnections.incrementAndGet();
                if (!forwarding.get()) {
                    closeQuietly(client);
                    continue;
                }
                connectUpstream(client);
            } catch (IOException ignored) {
                // Listener shutdown can race with accept.
            }
        }
    }

    private void connectUpstream(Socket client) {
        try {
            Socket upstream = new Socket(targetHost, targetPort);
            sockets.add(client);
            sockets.add(upstream);
            workers.execute(() -> bridge(client, upstream));
        } catch (IOException ignored) {
            closeQuietly(client);
        }
    }

    private void bridge(Socket client, Socket upstream) {
        workers.execute(() -> copy(client, upstream));
        copy(upstream, client);
    }

    private void copy(Socket source, Socket destination) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while (running.get() && (count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) {
            // Disconnects are the behavior under test.
        } finally {
            closePair(source, destination);
        }
    }

    private void closePair(Socket first, Socket second) {
        closeQuietly(first);
        closeQuietly(second);
        sockets.remove(first);
        sockets.remove(second);
    }

    @Override
    public void close() throws IOException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        listener.close();
        disconnectClients();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
