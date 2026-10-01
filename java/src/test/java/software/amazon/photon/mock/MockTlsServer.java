package software.amazon.photon.mock;

import java.io.*;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import software.amazon.photon.resp.*;

/**
 * Threaded TLS test server implementing the Photon cache contract. Uses a self-signed certificate
 * for testing.
 */
public class MockTlsServer implements Closeable {

    private final MockCache cache;
    private final SSLServerSocket serverSocket;
    private final Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final SSLContext sslContext;
    private final Object writeLock = new Object();
    private volatile OutputStream activeOutput;

    public MockTlsServer(MockCache cache) throws Exception {
        this.cache = cache;
        this.sslContext = createSelfSignedSslContext();
        SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        this.serverSocket = (SSLServerSocket) factory.createServerSocket(0); // random port
        this.serverThread = new Thread(this::acceptLoop, "mock-tls-server");
        this.serverThread.setDaemon(true);
        this.serverThread.start();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                Thread handler = new Thread(() -> handleClient(client), "mock-client-handler");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (running.get()) {
                    // unexpected
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try (InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream()) {
            activeOutput = out;
            byte[] buffer = new byte[8192];
            ByteArrayOutputStream accum = new ByteArrayOutputStream();

            while (running.get()) {
                int read = in.read(buffer);
                if (read < 0) break;
                accum.write(buffer, 0, read);

                // Try to decode commands from accumulated data
                byte[] data = accum.toByteArray();
                int pos = 0;
                while (pos < data.length) {
                    try {
                        DecodeResult result = RespCodec.decode(data, pos);
                        RespValue value = result.getValue();
                        pos = result.getPosition();

                        // Extract command array
                        if (value instanceof RespArray) {
                            RespArray array = (RespArray) value;
                            RespValue response = cache.handle(array.getElements());
                            if (response != null) {
                                write(out, encodeResponse(response));
                            } else if (cache.getFailureMode() == MockCache.FailureMode.HANG) {
                                // Keep the connection open without responding.
                            } else {
                                // DROP mode: close the connection.
                                return;
                            }
                        }
                    } catch (IncompleteFrame e) {
                        // Need more data
                        break;
                    }
                }

                // Keep unprocessed data
                if (pos > 0) {
                    byte[] remaining = new byte[data.length - pos];
                    System.arraycopy(data, pos, remaining, 0, remaining.length);
                    accum.reset();
                    accum.write(remaining, 0, remaining.length);
                }
            }
        } catch (IOException e) {
            // Client disconnected
        } finally {
            activeOutput = null;
        }
    }

    public void sendPush(RespPush push) throws IOException {
        sendRaw(encodeResponse(push));
    }

    public void sendRaw(byte[] frame) throws IOException {
        OutputStream out = activeOutput;
        if (out == null) {
            throw new IOException("No active client connection");
        }
        write(out, frame);
    }

    private void write(OutputStream out, byte[] frame) throws IOException {
        synchronized (writeLock) {
            out.write(frame);
            out.flush();
        }
    }

    private byte[] encodeResponse(RespValue value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        encodeValue(out, value);
        return out.toByteArray();
    }

    private void encodeValue(ByteArrayOutputStream out, RespValue value) {
        if (value instanceof RespSimpleString) {
            byte[] data = ((RespSimpleString) value).getValue();
            out.write('+');
            out.write(data, 0, data.length);
            out.write('\r');
            out.write('\n');
        } else if (value instanceof RespInteger) {
            String s = ":" + ((RespInteger) value).getValue() + "\r\n";
            byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes, 0, bytes.length);
        } else if (value instanceof RespError) {
            RespError err = (RespError) value;
            String s = "-" + err.getCode() + " " + err.getMessage() + "\r\n";
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        } else if (value instanceof RespBulkString) {
            byte[] data = ((RespBulkString) value).getValue();
            String header = "$" + data.length + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            out.write(headerBytes, 0, headerBytes.length);
            out.write(data, 0, data.length);
            out.write('\r');
            out.write('\n');
        } else if (value instanceof RespArray) {
            List<RespValue> elements = ((RespArray) value).getElements();
            String header = "*" + elements.size() + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            out.write(headerBytes, 0, headerBytes.length);
            for (RespValue element : elements) {
                encodeValue(out, element);
            }
        } else if (value instanceof RespMap) {
            java.util.Map<RespValue, RespValue> entries = ((RespMap) value).getEntries();
            String header = "%" + entries.size() + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            out.write(headerBytes, 0, headerBytes.length);
            for (java.util.Map.Entry<RespValue, RespValue> entry : entries.entrySet()) {
                encodeValue(out, entry.getKey());
                encodeValue(out, entry.getValue());
            }
        } else if (value instanceof RespPush) {
            List<RespValue> elements = ((RespPush) value).getElements();
            String header = ">" + elements.size() + "\r\n";
            byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
            out.write(headerBytes, 0, headerBytes.length);
            for (RespValue element : elements) {
                encodeValue(out, element);
            }
        } else if (value instanceof RespNull) {
            byte[] bytes = "$-1\r\n".getBytes(StandardCharsets.US_ASCII);
            out.write(bytes, 0, bytes.length);
        }
    }

    private SSLContext createSelfSignedSslContext() throws Exception {
        // Generate a self-signed certificate
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048, new SecureRandom());
        KeyPair keyPair = keyGen.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(keyPair);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry(
                "server", keyPair.getPrivate(), "password".toCharArray(), new Certificate[] {cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "password".toCharArray());

        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        ks.setCertificateEntry("ca", cert);
        tmf.init(ks);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    private X509Certificate generateSelfSignedCert(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now);
        Date notAfter = new Date(now + 365L * 24 * 60 * 60 * 1000);

        // Use CN=localhost so hostname verification passes when connecting to localhost
        X500Name subject = new X500Name("CN=localhost");
        X509CertificateHolder holder =
                new JcaX509v3CertificateBuilder(
                                subject, BigInteger.valueOf(now), notBefore, notAfter, subject, keyPair.getPublic())
                        .build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        serverSocket.close();
        serverThread.interrupt();
    }
}
