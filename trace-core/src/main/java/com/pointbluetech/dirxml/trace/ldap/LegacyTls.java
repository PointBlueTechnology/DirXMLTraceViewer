package com.pointbluetech.dirxml.trace.ldap;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Opt-in re-enabling of static-RSA key exchange ({@code TLS_RSA_*}) cipher suites.
 * <p>
 * eDirectory's LDAPS listener often offers only these (e.g. AES256-GCM-SHA384), and JDK 24+ disables
 * them. The JDK reads {@code jdk.tls.disabledAlgorithms} once, when the TLS stack first initializes,
 * so the change only works before the first LDAPS connection and then stays in effect for the process.
 */
public final class LegacyTls {

    private static final String PROPERTY = "jdk.tls.disabledAlgorithms";
    private static final String RSA_KEY_EXCHANGE = "TLS_RSA_*";

    private static boolean enabled;
    private static boolean tlsInitialized;

    private LegacyTls() {
    }

    /** Re-enables the suites; returns false if TLS already initialized without them (restart needed). */
    public static synchronized boolean enable() {
        if (enabled) {
            return true;
        }
        if (tlsInitialized) {
            return false;
        }
        String disabled = Security.getProperty(PROPERTY);
        if (disabled != null) {
            Security.setProperty(PROPERTY, Arrays.stream(disabled.split(","))
                    .map(String::trim)
                    .filter(a -> !a.equals(RSA_KEY_EXCHANGE))
                    .collect(Collectors.joining(", ")));
        }
        enabled = true;
        return true;
    }

    public static synchronized boolean isEnabled() {
        return enabled;
    }

    /** Records that the TLS stack has read its settings, so later calls to {@link #enable()} are too late. */
    static synchronized void markTlsInitialized() {
        tlsInitialized = true;
    }
}
