// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.amazon.elasticache.integrations.session;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import software.amazon.elasticache.integrations.exception.ElastiCacheIntegrationsConfigError;

/**
 * Builds the TLS context for the connection to the cache.
 *
 * <p>The connector checks the certificate's host name whenever it uses a context from the system
 * trust store or from a CA bundle. Only {@code verify(false)} skips both checks.
 */
final class CacheTls {

    private CacheTls() {}

    /**
     * Create the context for the cache connection.
     *
     * @param verify false disables certificate and host name verification (self-signed test rigs)
     * @param caCerts a PEM CA bundle to trust instead of the system trust store, or null
     * @throws ElastiCacheIntegrationsConfigError if the CA bundle cannot be read or holds no
     *     certificate
     */
    static SSLContext create(boolean verify, Path caCerts) {
        try {
            if (!verify) {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {new TrustEverything()}, null);
                return context;
            }
            if (caCerts == null) {
                return SSLContext.getDefault();
            }
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            try (InputStream in = Files.newInputStream(caCerts)) {
                Collection<? extends Certificate> certificates =
                        CertificateFactory.getInstance("X.509").generateCertificates(in);
                if (certificates.isEmpty()) {
                    throw new ElastiCacheIntegrationsConfigError(
                            "caCerts file " + caCerts + " contains no PEM certificate");
                }
                int index = 0;
                for (Certificate certificate : certificates) {
                    trusted.setCertificateEntry("ca-" + index++, certificate);
                }
            }
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trusted);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new ElastiCacheIntegrationsConfigError(
                    "Cannot use caCerts file " + caCerts + " as a PEM CA bundle: " + e.getMessage(), e);
        }
    }

    /**
     * Accepts any certificate. An {@link X509ExtendedTrustManager} is required so the JDK does not
     * wrap it in a manager that still checks the host name.
     */
    @SuppressWarnings("TrustAllX509TrustManager")
    private static final class TrustEverything extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
