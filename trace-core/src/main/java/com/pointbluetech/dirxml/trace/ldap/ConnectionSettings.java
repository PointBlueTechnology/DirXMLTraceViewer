package com.pointbluetech.dirxml.trace.ldap;

/**
 * @param searchBase where to look for driver sets; empty searches the whole tree
 * @param legacyCiphers allow static-RSA key exchange suites (LDAPS only); see {@link LegacyTls}
 */
public record ConnectionSettings(String host, int port, boolean ssl, boolean legacyCiphers,
                                 String bindDn, char[] password, String searchBase) {

    /** The same credentials and TLS options, for a connection to another server in the driver set. */
    public ConnectionSettings withHost(String otherHost) {
        return new ConnectionSettings(otherHost, port, ssl, legacyCiphers, bindDn, password, searchBase);
    }

    public String display() {
        return (ssl ? "ldaps://" : "ldap://") + host + ":" + port;
    }
}
