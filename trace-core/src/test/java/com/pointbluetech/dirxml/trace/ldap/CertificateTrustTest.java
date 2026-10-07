package com.pointbluetech.dirxml.trace.ldap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end: real TLS handshakes against a local server with a self-signed certificate. */
class CertificateTrustTest {

    @TempDir
    Path dir;

    private Preferences node;
    private AcceptedCertificates store;
    private final List<CertificateTrust.Request> asked = new ArrayList<>();

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("dirxml-trace-viewer-test-" + System.nanoTime());
        store = new AcceptedCertificates(node);
        CertificateTrust.useStoreForTesting(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        CertificateTrust.setPrompt(null);
        CertificateTrust.useStoreForTesting(AcceptedCertificates.userStore());
        node.removeNode();
    }

    private void answer(CertificateTrust.Decision d) {
        CertificateTrust.setPrompt(r -> {
            asked.add(r);
            return d;
        });
    }

    @Test
    void untrustedCertificateIsRefusedWhenUserCancels() throws Exception {
        try (TlsServer server = new TlsServer(selfSigned("a"))) {
            answer(CertificateTrust.Decision.REJECT);
            assertThrows(IOException.class, () -> server.handshake());
            assertEquals(1, asked.size());
            assertEquals("localhost", asked.get(0).host());
            assertNull(asked.get(0).previous());
            assertTrue(store.list().isEmpty());
        }
    }

    @Test
    void noPromptInstalledMeansRefused() throws Exception {
        try (TlsServer server = new TlsServer(selfSigned("a"))) {
            CertificateTrust.setPrompt(null);
            assertThrows(IOException.class, () -> server.handshake());
        }
    }

    @Test
    void trustThisTimeIsNotRemembered() throws Exception {
        try (TlsServer server = new TlsServer(selfSigned("a"))) {
            answer(CertificateTrust.Decision.ONCE);
            server.handshake();
            server.handshake(); // same session: not asked again
            assertEquals(1, asked.size());
            assertTrue(store.list().isEmpty());
            CertificateTrust.clearSession();
            server.handshake();
            assertEquals(2, asked.size());
        }
    }

    @Test
    void rememberedCertificateIsTrustedUntilItChangesOrIsRemoved() throws Exception {
        Path first = selfSigned("a");
        int port;
        try (TlsServer server = new TlsServer(first)) {
            port = server.port();
            answer(CertificateTrust.Decision.REMEMBER);
            server.handshake();
            CertificateTrust.clearSession();
            server.handshake(); // remembered: not asked again
            assertEquals(1, asked.size());
            AcceptedCertificates.Entry saved = store.find(AcceptedCertificates.serverKey("localhost", port));
            assertNotNull(saved);
            assertEquals(AcceptedCertificates.sha256(asked.get(0).certificate()), saved.sha256());

            store.remove(saved.server());
            server.handshake(); // removed: asked again
            assertEquals(2, asked.size());
        }

        // Same server, different certificate: asked again, with the remembered one for comparison.
        try (TlsServer server = new TlsServer(selfSigned("b"), port)) {
            answer(CertificateTrust.Decision.REJECT);
            assertThrows(IOException.class, () -> server.handshake());
            CertificateTrust.Request changed = asked.get(asked.size() - 1);
            assertNotNull(changed.previous());
            assertTrue(!changed.previous().sha256().equals(AcceptedCertificates.sha256(changed.certificate())));
        }

        store.clear();
        assertTrue(store.list().isEmpty());
    }

    @Test
    void certificateFromATrustedCaIsAcceptedWithoutAsking() throws Exception {
        Path[] pki = caAndServer("localhost");
        String before = System.getProperty("javax.net.ssl.trustStore");
        System.setProperty("javax.net.ssl.trustStore", pki[0].toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        try (TlsServer server = new TlsServer(pki[1])) {
            answer(CertificateTrust.Decision.REJECT);
            server.handshake();
            assertTrue(asked.isEmpty(), "a CA-trusted certificate for the right host must not prompt");
        } finally {
            restore("javax.net.ssl.trustStore", before);
            System.clearProperty("javax.net.ssl.trustStorePassword");
        }
    }

    @Test
    void trustedCertificateForAnotherHostStillAsks() throws Exception {
        Path[] pki = caAndServer("some-other-host");
        String before = System.getProperty("javax.net.ssl.trustStore");
        System.setProperty("javax.net.ssl.trustStore", pki[0].toString());
        System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
        try (TlsServer server = new TlsServer(pki[1])) {
            answer(CertificateTrust.Decision.REJECT);
            assertThrows(IOException.class, () -> server.handshake());
            assertEquals(1, asked.size());
            assertTrue(asked.get(0).problem().toLowerCase().contains("localhost"), asked.get(0).problem());
        } finally {
            restore("javax.net.ssl.trustStore", before);
            System.clearProperty("javax.net.ssl.trustStorePassword");
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    /** Returns {truststore holding a new CA, server keystore with a CA-signed certificate for dnsName}. */
    private Path[] caAndServer(String dnsName) throws Exception {
        Path ca = dir.resolve("ca.p12"), trust = dir.resolve("trust.p12"), server = dir.resolve("server-" + dnsName + ".p12");
        Path caCert = dir.resolve("ca.pem"), req = dir.resolve("server.csr"), signed = dir.resolve("server.pem");
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=Test CA",
                "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", ca, "-storepass", "changeit");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", ca, "-storepass", "changeit", "-file", caCert);
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCert, "-storetype", "PKCS12", "-keystore", trust,
                "-storepass", "changeit");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname",
                "CN=" + dnsName, "-storetype", "PKCS12", "-keystore", server, "-storepass", "changeit");
        keytool("-certreq", "-alias", "server", "-keystore", server, "-storepass", "changeit", "-file", req);
        keytool("-gencert", "-rfc", "-alias", "ca", "-keystore", ca, "-storepass", "changeit", "-infile", req,
                "-outfile", signed, "-validity", "2", "-ext", "san=dns:" + dnsName, "-ext", "eku=serverAuth");
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCert, "-keystore", server, "-storepass", "changeit");
        keytool("-importcert", "-alias", "server", "-file", signed, "-keystore", server, "-storepass", "changeit");
        return new Path[]{trust, server};
    }

    private static void keytool(Object... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        for (Object a : args) cmd.add(a.toString());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "keytool " + cmd + ": " + out);
    }

    private Path selfSigned(String alias) throws Exception {
        Path ks = dir.resolve(alias + ".p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=test-" + alias, "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "keytool failed");
        return ks;
    }

    /** A one-connection-at-a-time TLS server that echoes one byte. */
    private static final class TlsServer implements AutoCloseable {
        private final SSLServerSocket socket;
        private final Thread thread;

        TlsServer(Path keystore) throws Exception {
            this(keystore, 0);
        }

        TlsServer(Path keystore, int port) throws Exception {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream in = new FileInputStream(keystore.toFile())) {
                ks.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, "changeit".toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            socket = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(port);
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (var s = socket.accept(); InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                        int b = in.read();
                        if (b >= 0) out.write(b);
                    } catch (IOException ignored) {
                        // client refused the certificate, or server closed
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        void handshake() throws Exception {
            try (SSLSocket s = (SSLSocket) CertificateTrust.socketFactory("localhost", port()).createSocket("localhost", port())) {
                s.getOutputStream().write(42);
                assertEquals(42, s.getInputStream().read());
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
