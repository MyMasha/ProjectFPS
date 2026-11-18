package dev.purefps.core;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pont TLS moderne pour Minecraft 1.8.9 (Java 8u51+ -> Java 8u442+) :
 * - Ajoute les autorités racines modernes utilisées par Mojang / Microsoft Azure (DigiCert Global Root G2/G3,
 *   Microsoft RSA Root Certificate Authority 2017) et Let's Encrypt (ISRG Root X1) au TrustManager par défaut,
 *   corrigeant l'erreur trompeuse "The authentication servers are currently down for maintenance" sur les JRE anciens.
 * - Combine le magasin cacerts du JRE, le bundle Mozilla embarqué et (sous Windows) WINDOWS-ROOT sans jamais
 *   affaiblir la vérification cryptographique X.509.
 * - Active automatiquement TLSv1.2 (et TLSv1.3 si supporté par le JRE) sur toutes les connexions HttpsURLConnection.
 */
public final class ModernTlsTrustHelper {

    public static final String BUNDLED_ROOTS_RESOURCE = "/assets/purefps/certs/modern_roots.crt";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static volatile boolean active = false;

    private ModernTlsTrustHelper() {
    }

    public static boolean installModernRootTrustIfNeeded() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return active;
        }
        try {
            try {
                Security.setProperty("crypto.policy", "unlimited");
            } catch (Throwable ignored) {
            }

            List<X509TrustManager> delegates = new ArrayList<X509TrustManager>(3);

            try {
                X509TrustManager jvmDefault = buildTrustManagerFromKeyStore(null);
                if (jvmDefault != null
                        && jvmDefault.getAcceptedIssuers() != null
                        && jvmDefault.getAcceptedIssuers().length > 0) {
                    delegates.add(jvmDefault);
                }
            } catch (Throwable ignored) {
            }

            X509TrustManager bundled = buildBundledRootsTrustManager();
            if (bundled != null) {
                delegates.add(bundled);
            }

            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                try {
                    KeyStore winRoot = KeyStore.getInstance("WINDOWS-ROOT");
                    winRoot.load(null, null);
                    X509TrustManager winTm = buildTrustManagerFromKeyStore(winRoot);
                    if (winTm != null
                            && winTm.getAcceptedIssuers() != null
                            && winTm.getAcceptedIssuers().length > 0) {
                        delegates.add(winTm);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (delegates.isEmpty()) {
                return false;
            }

            X509TrustManager composite = new CompositeX509TrustManager(delegates);
            SSLContext sslContext = createBestTlsContext();
            sslContext.init(null, new TrustManager[]{composite}, new SecureRandom());

            SSLSocketFactory modernFactory = new ModernProtocolSSLSocketFactory(sslContext.getSocketFactory());
            SSLContext.setDefault(sslContext);
            HttpsURLConnection.setDefaultSSLSocketFactory(modernFactory);
            active = true;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static List<X509Certificate> loadBundledRootCertificates() throws Exception {
        InputStream in = ModernTlsTrustHelper.class.getResourceAsStream(BUNDLED_ROOTS_RESOURCE);
        if (in == null) {
            throw new IOException("Missing resource: " + BUNDLED_ROOTS_RESOURCE);
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            List<X509Certificate> result = new ArrayList<X509Certificate>(certs.size());
            for (Certificate cert : certs) {
                if (cert instanceof X509Certificate) {
                    result.add((X509Certificate) cert);
                }
            }
            return result;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    static X509TrustManager buildBundledRootsTrustManager() {
        try {
            List<X509Certificate> roots = loadBundledRootCertificates();
            if (roots.isEmpty()) {
                return null;
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            for (int i = 0; i < roots.size(); i++) {
                ks.setCertificateEntry("mashclient-root-" + i, roots.get(i));
            }
            return buildTrustManagerFromKeyStore(ks);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static X509TrustManager buildTrustManagerFromKeyStore(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        TrustManager[] managers = tmf.getTrustManagers();
        if (managers != null) {
            for (int i = 0; i < managers.length; i++) {
                if (managers[i] instanceof X509TrustManager) {
                    return (X509TrustManager) managers[i];
                }
            }
        }
        return null;
    }

    private static SSLContext createBestTlsContext() throws Exception {
        try {
            return SSLContext.getInstance("TLSv1.2");
        } catch (Throwable ignored) {
            return SSLContext.getInstance("TLS");
        }
    }

    static final class CompositeX509TrustManager implements X509TrustManager {
        private final X509TrustManager[] delegates;
        private final X509Certificate[] acceptedIssuers;

        CompositeX509TrustManager(List<X509TrustManager> managers) {
            this.delegates = managers.toArray(new X509TrustManager[0]);
            Set<X509Certificate> merged = new LinkedHashSet<X509Certificate>();
            for (int i = 0; i < this.delegates.length; i++) {
                X509Certificate[] issuers = this.delegates[i].getAcceptedIssuers();
                if (issuers != null) {
                    merged.addAll(Arrays.asList(issuers));
                }
            }
            this.acceptedIssuers = merged.toArray(new X509Certificate[0]);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (this.delegates.length == 0) {
                throw new CertificateException("No X509TrustManager available");
            }
            this.delegates[0].checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            CertificateException firstError = null;
            for (int i = 0; i < this.delegates.length; i++) {
                try {
                    this.delegates[i].checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException ex) {
                    if (firstError == null) {
                        firstError = ex;
                    }
                } catch (RuntimeException rex) {
                    if (firstError == null) {
                        firstError = new CertificateException(rex.getMessage(), rex);
                    }
                }
            }
            if (firstError != null) {
                throw firstError;
            }
            throw new CertificateException("Untrusted server certificate chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return this.acceptedIssuers.clone();
        }
    }

    private static final class ModernProtocolSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        ModernProtocolSSLSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private static Socket enableModernTls(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket ssl = (SSLSocket) socket;
                String[] supported = ssl.getSupportedProtocols();
                if (supported != null) {
                    List<String> preferred = new ArrayList<String>(2);
                    for (int i = 0; i < supported.length; i++) {
                        String p = supported[i];
                        if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) {
                            preferred.add(p);
                        }
                    }
                    if (!preferred.isEmpty()) {
                        ssl.setEnabledProtocols(preferred.toArray(new String[0]));
                    }
                }
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return this.delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return this.delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enableModernTls(this.delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return enableModernTls(this.delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return enableModernTls(this.delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableModernTls(this.delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return enableModernTls(this.delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket() throws IOException {
            return enableModernTls(this.delegate.createSocket());
        }
    }
}
