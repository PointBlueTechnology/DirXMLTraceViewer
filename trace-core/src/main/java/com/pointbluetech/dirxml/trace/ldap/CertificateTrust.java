package com.pointbluetech.dirxml.trace.ldap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * Server certificate checking for LDAPS. A certificate is accepted without asking when the system
 * trusts it: its chain leads to a CA in Java's trust store and it matches the server's name or
 * address. Otherwise (self-signed, eDirectory tree CA, expired, name mismatch) the user is asked
 * through the installed {@link Prompt}. They can trust it for this session, trust and remember it
 * ({@link AcceptedCertificates}), or refuse, which fails the connection.
 */
public final class CertificateTrust {

    public enum Decision { REMEMBER, ONCE, REJECT }

    /**
     * What the user is asked about.
     *
     * @param problem  why the system does not trust the certificate
     * @param previous the different certificate remembered earlier for this server, or null
     */
    public record Request(String host, int port, X509Certificate[] chain, String problem,
                          AcceptedCertificates.Entry previous) {
        public X509Certificate certificate() {
            return chain[0];
        }
    }

    /** Asks the user; may be called on any thread and should block until they decide. */
    public interface Prompt {
        Decision ask(Request request);
    }

    private static volatile Prompt prompt;
    private static AcceptedCertificates store = AcceptedCertificates.userStore();
    /** Certificates trusted for this run only ("Trust This Time"), by server. */
    private static final Map<String, String> acceptedThisSession = new HashMap<>();

    private CertificateTrust() {
    }

    /** Installs the UI prompt. Without one, untrusted certificates are refused. */
    public static void setPrompt(Prompt p) {
        prompt = p;
    }

    public static AcceptedCertificates store() {
        return store;
    }

    static synchronized void useStoreForTesting(AcceptedCertificates s) {
        store = s;
        acceptedThisSession.clear();
    }

    /** Forgets certificates trusted for this session only. */
    public static synchronized void clearSession() {
        acceptedThisSession.clear();
    }

    /** A socket factory for {@code host:port} that checks the server certificate as described above. */
    public static SSLSocketFactory socketFactory(String host, int port) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{trustManager(host, port)}, new SecureRandom());
        return new HostnameCheckingFactory(ctx.getSocketFactory());
    }

    static X509ExtendedTrustManager trustManager(String host, int port) throws GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager system) {
                return new PromptingTrustManager(host, port, system);
            }
        }
        throw new GeneralSecurityException("No X509 trust manager available");
    }

    /** Called when the system does not trust the chain; returns normally only if the user accepts it. */
    static void decide(String host, int port, X509Certificate[] chain, CertificateException systemVerdict)
            throws CertificateException {
        String server = AcceptedCertificates.serverKey(host, port);
        String fingerprint = AcceptedCertificates.sha256(chain[0]);
        AcceptedCertificates.Entry saved;
        synchronized (CertificateTrust.class) {
            if (fingerprint.equals(acceptedThisSession.get(server))) {
                return;
            }
            saved = store.find(server);
            if (saved != null && saved.sha256().equals(fingerprint)) {
                return;
            }
        }
        Prompt p = prompt;
        String problem = systemVerdict.getMessage() == null ? systemVerdict.toString() : systemVerdict.getMessage();
        Decision d = p == null ? Decision.REJECT : p.ask(new Request(host, port, chain, problem, saved));
        synchronized (CertificateTrust.class) {
            switch (d == null ? Decision.REJECT : d) {
                case REMEMBER -> store.remember(server, chain[0]);
                case ONCE -> acceptedThisSession.put(server, fingerprint);
                case REJECT -> {
                    CertificateException e = new CertificateException(
                            "The certificate for " + server + " was not accepted (" + problem + ")");
                    e.initCause(systemVerdict);
                    throw e;
                }
            }
        }
    }

    /** Defers to the system's checks, and to {@link #decide} when they fail. */
    private static final class PromptingTrustManager extends X509ExtendedTrustManager {
        private final String host;
        private final int port;
        private final X509ExtendedTrustManager system;

        PromptingTrustManager(String host, int port, X509ExtendedTrustManager system) {
            this.host = host;
            this.port = port;
            this.system = system;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType, socket);
            } catch (CertificateException e) {
                decide(host, port, chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType, engine);
            } catch (CertificateException e) {
                decide(host, port, chain, e);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                decide(host, port, chain, e);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            system.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            system.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system.getAcceptedIssuers();
        }
    }

    /** Turns on LDAPS hostname verification on each socket, before its handshake. */
    private static final class HostnameCheckingFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        HostnameCheckingFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        private Socket check(Socket s) {
            if (s instanceof SSLSocket ssl) {
                SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("LDAPS");
                ssl.setSSLParameters(params);
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return check(delegate.createSocket()); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return check(delegate.createSocket(s, host, port, autoClose));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return check(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return check(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return check(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            return check(delegate.createSocket(address, port, local, localPort));
        }
    }
}
