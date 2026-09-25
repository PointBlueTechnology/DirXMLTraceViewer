package com.pointbluetech.dirxml.trace.ldap;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Server certificates the user chose to trust permanently, pinned per {@code host:port} by SHA-256
 * fingerprint (like SSH's known_hosts). Kept in the user's preferences.
 */
public final class AcceptedCertificates {

    /** One remembered certificate. */
    public record Entry(String server, String sha256, String subject, String issuer, Instant notAfter,
                        Instant acceptedAt) {
    }

    private final Preferences root;

    public AcceptedCertificates(Preferences root) {
        this.root = root;
    }

    /** The store in the user's preferences. */
    public static AcceptedCertificates userStore() {
        return new AcceptedCertificates(Preferences.userNodeForPackage(AcceptedCertificates.class).node("acceptedCertificates"));
    }

    public static String serverKey(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    public synchronized Entry find(String server) {
        try {
            if (!root.nodeExists(server)) {
                return null;
            }
            Preferences n = root.node(server);
            String sha = n.get("sha256", null);
            if (sha == null) {
                return null;
            }
            return new Entry(server, sha, n.get("subject", ""), n.get("issuer", ""),
                    Instant.ofEpochMilli(n.getLong("notAfter", 0)), Instant.ofEpochMilli(n.getLong("acceptedAt", 0)));
        } catch (BackingStoreException | IllegalArgumentException e) {
            return null;
        }
    }

    public synchronized void remember(String server, X509Certificate cert) {
        Preferences n = root.node(server);
        n.put("sha256", sha256(cert));
        n.put("subject", cert.getSubjectX500Principal().getName());
        n.put("issuer", cert.getIssuerX500Principal().getName());
        n.putLong("notAfter", cert.getNotAfter().getTime());
        n.putLong("acceptedAt", System.currentTimeMillis());
        flush();
    }

    public synchronized List<Entry> list() {
        List<Entry> entries = new ArrayList<>();
        try {
            for (String server : root.childrenNames()) {
                Entry e = find(server);
                if (e != null) entries.add(e);
            }
        } catch (BackingStoreException ignored) {
        }
        entries.sort(Comparator.comparing(Entry::server));
        return entries;
    }

    public synchronized void remove(String server) {
        try {
            if (root.nodeExists(server)) {
                root.node(server).removeNode();
                flush();
            }
        } catch (BackingStoreException ignored) {
        }
    }

    public synchronized void clear() {
        try {
            for (String server : root.childrenNames()) {
                root.node(server).removeNode();
            }
            flush();
        } catch (BackingStoreException ignored) {
        }
    }

    private void flush() {
        try {
            root.flush();
        } catch (BackingStoreException ignored) {
        }
    }

    /** SHA-256 fingerprint as colon-separated upper-case hex, as browsers and keytool show it. */
    public static String sha256(X509Certificate cert) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
