package com.pointbluetech.dirxml.trace.ldap;

import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPConnection;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPException;
import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.LDAPExtendedResponse;
import com.novell.ldap.LDAPJSSESecureSocketFactory;
import com.novell.ldap.LDAPModification;
import com.novell.ldap.LDAPReferralException;
import com.novell.ldap.LDAPSearchConstraints;
import com.novell.ldap.LDAPSearchResults;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Connection to one eDirectory server: driver discovery, that server's view of driver state and
 * trace level (both are per-server), and driver start/stop/restart through the IDM LDAP extensions
 * ({@link IdmExtendedOperations}).
 */
public final class LdapSession implements AutoCloseable {

    private static final String ATTR_DRIVER_SET_TRACE_LEVEL = "DirXML-DriverTraceLevel";
    private static final String ATTR_DRIVER_TRACE_LEVEL = "DirXML-TraceLevel";
    private static final String ATTR_SERVER_LIST = "DirXML-ServerList";
    /** Designer's "Trace name" setting; when present the engine prints it instead of the driver CN. */
    private static final String[] ATTR_TRACE_NAME = {"DirXML-DriverTraceName", "DirXML-TraceName"};
    private static final int TIME_LIMIT_MS = 30_000;
    private static final boolean DEBUG = Boolean.getBoolean("dirxml.debug");

    private final ConnectionSettings settings;
    private final LDAPConnection conn;

    private LdapSession(ConnectionSettings settings, LDAPConnection conn) {
        this.settings = settings;
        this.conn = conn;
    }

    public static LdapSession open(ConnectionSettings settings) throws LDAPException {
        return new LdapSession(settings, connect(settings));
    }

    /** Opens and binds a new connection; each event stream needs a dedicated one. */
    static LDAPConnection connect(ConnectionSettings s) throws LDAPException {
        if (s.ssl() && s.legacyCiphers() && !LegacyTls.enable()) {
            throw new LDAPException("Restart the viewer to allow legacy RSA ciphers",
                    LDAPException.CONNECT_ERROR,
                    "TLS was already initialized in this session without them; the setting has been saved.");
        }
        LDAPConnection c;
        if (s.ssl()) {
            c = new LDAPConnection(new LDAPJSSESecureSocketFactory(sslFactory(s)));
            LegacyTls.markTlsInitialized();
        } else {
            c = new LDAPConnection();
        }
        c.connect(s.host(), s.port());
        try {
            c.bind(LDAPConnection.LDAP_V3, s.bindDn(), utf8(s.password()));
        } catch (LDAPException e) {
            safeDisconnect(c);
            throw e;
        }
        return c;
    }

    public ConnectionSettings settings() {
        return settings;
    }

    /** DN of the server this connection is to (root DSE {@code dsaName}), or null if unavailable. */
    public String serverDn() {
        try {
            return strAttr(conn.read("", new String[]{"dsaName"}), "dsaName");
        } catch (LDAPException e) {
            return null;
        }
    }

    /**
     * Finds all driver sets under the search base with their drivers and servers, sorted by name.
     * Statuses are left empty: each server's view has to be read over a connection to that server.
     */
    public List<DirXmlObject> discover() throws LDAPException {
        String base = settings.searchBase() == null ? "" : settings.searchBase().trim();
        String connectedDn = serverDn();
        List<DirXmlObject> sets = new ArrayList<>();
        for (LDAPEntry e : search(base, LDAPConnection.SCOPE_SUB, "(objectClass=DirXML-DriverSet)", ATTR_SERVER_LIST)) {
            List<DirXmlObject> drivers = new ArrayList<>();
            for (LDAPEntry d : search(e.getDN(), LDAPConnection.SCOPE_ONE, "(objectClass=DirXML-Driver)")) {
                drivers.add(toObject(d, DirXmlObject.Kind.DRIVER, List.of(), List.of()));
            }
            drivers.sort(Comparator.comparing(DirXmlObject::name, String.CASE_INSENSITIVE_ORDER));
            List<ServerInfo> servers = new ArrayList<>();
            LDAPAttribute list = e.getAttribute(ATTR_SERVER_LIST);
            if (list != null) {
                for (String serverDn : list.getStringValueArray()) {
                    servers.add(resolveServer(serverDn, connectedDn));
                }
            }
            servers.sort(Comparator.comparing(ServerInfo::name, String.CASE_INSENSITIVE_ORDER));
            if (DEBUG) System.err.println("[dirxml.debug] driver set " + e.getDN() + " servers " + servers);
            sets.add(toObject(e, DirXmlObject.Kind.DRIVER_SET, servers, drivers));
        }
        sets.sort(Comparator.comparing(DirXmlObject::name, String.CASE_INSENSITIVE_ORDER));
        return sets;
    }

    /**
     * Works out where to reach a server: the host we are already connected to if it is this server,
     * else the IPv4 address in its {@code networkAddress}, else its CN as a host name.
     */
    private ServerInfo resolveServer(String dn, String connectedDn) {
        String rdn = dn.split(",", 2)[0];
        String name = rdn.substring(rdn.indexOf('=') + 1);
        if (connectedDn != null && sameDn(dn, connectedDn)) {
            return new ServerInfo(dn, name, settings.host());
        }
        try {
            LDAPEntry e = conn.read(dn, new String[]{"cn", "networkAddress"});
            String cn = strAttr(e, "cn");
            name = cn == null ? name : cn;
            LDAPAttribute addrs = e.getAttribute("networkAddress");
            if (addrs != null) {
                for (byte[] v : addrs.getByteValueArray()) {
                    String ip = ipv4FromNetworkAddress(v);
                    if (DEBUG) System.err.println("[dirxml.debug] " + dn + " networkAddress " + hex(v) + " -> " + ip);
                    if (ip != null) {
                        return new ServerInfo(dn, name, ip);
                    }
                }
            }
        } catch (LDAPException ex) {
            if (DEBUG) System.err.println("[dirxml.debug] read server " + dn + " failed: " + ex);
        }
        return new ServerInfo(dn, name, name);
    }

    /**
     * eDirectory renders Net Address values over LDAP as {@code <type>#<address bytes>}. For IP
     * (type 1) and TCP (type 9) the address is a 2-byte port followed by the 4-byte IPv4 address.
     */
    static String ipv4FromNetworkAddress(byte[] v) {
        int hash = -1;
        for (int i = 0; i < v.length && i < 4; i++) {
            if (v[i] == '#') {
                hash = i;
                break;
            }
        }
        if (hash <= 0) return null;
        int type;
        try {
            type = Integer.parseInt(new String(v, 0, hash, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return null;
        }
        if ((type != 1 && type != 9) || v.length - hash - 1 < 6) return null;
        int a = hash + 3;
        return (v[a] & 0xff) + "." + (v[a + 1] & 0xff) + "." + (v[a + 2] & 0xff) + "." + (v[a + 3] & 0xff);
    }

    /** Reads this server's view of an object: engine state (drivers only) and trace level. */
    public DriverStatus readStatus(DirXmlObject o, ServerInfo server) {
        try {
            int state = o.kind() == DirXmlObject.Kind.DRIVER ? driverState(o.dn()) : DriverStatus.UNKNOWN;
            String attr = o.traceLevelAttribute();
            LDAPEntry e = conn.read(o.dn(), new String[]{attr});
            return new DriverStatus(server, state, intAttr(e, attr), null);
        } catch (LDAPException ex) {
            return DriverStatus.failed(server, describe(ex));
        }
    }

    public void setTraceLevel(DirXmlObject o, int level) throws LDAPException {
        conn.modify(o.dn(), new LDAPModification(LDAPModification.REPLACE,
                new LDAPAttribute(o.traceLevelAttribute(), Integer.toString(level))));
    }

    public int driverState(String driverDn) throws LDAPException {
        LDAPExtendedResponse r = extended(IdmExtendedOperations.request(IdmExtendedOperations.GET_DRIVER_STATE, driverDn));
        return IdmExtendedOperations.decodeDriverState(r.getValue());
    }

    public void startDriver(String driverDn) throws LDAPException {
        extended(IdmExtendedOperations.request(IdmExtendedOperations.START_DRIVER, driverDn));
    }

    public void stopDriver(String driverDn) throws LDAPException {
        extended(IdmExtendedOperations.request(IdmExtendedOperations.STOP_DRIVER, driverDn));
    }

    public void restartDriver(String driverDn) throws LDAPException {
        extended(IdmExtendedOperations.request(IdmExtendedOperations.RESTART_DRIVER, driverDn));
    }

    private LDAPExtendedResponse extended(LDAPExtendedOperation op) throws LDAPException {
        LDAPExtendedResponse r = conn.extendedOperation(op);
        if (r.getResultCode() != LDAPException.SUCCESS) {
            throw new LDAPException(r.getErrorMessage(), r.getResultCode(), r.getErrorMessage());
        }
        return r;
    }

    static String describe(LDAPException ex) {
        String server = ex.getLDAPErrorMessage();
        return server != null && !server.isBlank() ? ex.resultCodeToString() + ": " + server : ex.getMessage();
    }

    private List<LDAPEntry> search(String base, int scope, String filter, String... extraAttrs) throws LDAPException {
        List<String> attrs = new ArrayList<>(List.of("cn"));
        attrs.addAll(Arrays.asList(ATTR_TRACE_NAME));
        attrs.addAll(Arrays.asList(extraAttrs));

        LDAPSearchConstraints cons = new LDAPSearchConstraints();
        cons.setTimeLimit(TIME_LIMIT_MS);
        cons.setMaxResults(0);
        cons.setReferralFollowing(false);
        LDAPSearchResults results = conn.search(base, scope, filter, attrs.toArray(String[]::new), false, cons);
        List<LDAPEntry> entries = new ArrayList<>();
        while (results.hasMore()) {
            try {
                entries.add(results.next());
            } catch (LDAPReferralException ignored) {
                // Objects on partitions this server doesn't hold; their trace isn't visible here anyway.
            }
        }
        return entries;
    }

    private static DirXmlObject toObject(LDAPEntry e, DirXmlObject.Kind kind, List<ServerInfo> servers,
                                         List<DirXmlObject> drivers) {
        String name = strAttr(e, "cn");
        if (name == null) {
            String rdn = e.getDN().split(",", 2)[0];
            name = rdn.substring(rdn.indexOf('=') + 1);
        }
        String traceName = null;
        for (String a : ATTR_TRACE_NAME) {
            traceName = traceName == null ? strAttr(e, a) : traceName;
        }
        return new DirXmlObject(kind, e.getDN(), name, traceName == null || traceName.isBlank() ? name : traceName,
                servers, List.of(), drivers);
    }

    private static String strAttr(LDAPEntry e, String name) {
        LDAPAttribute a = e.getAttribute(name);
        return a == null ? null : a.getStringValue();
    }

    private static int intAttr(LDAPEntry e, String name) {
        String v = strAttr(e, name);
        try {
            return v == null ? DriverStatus.UNKNOWN : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return DriverStatus.UNKNOWN;
        }
    }

    static boolean sameDn(String a, String b) {
        return normalizeDn(a).equals(normalizeDn(b));
    }

    private static String normalizeDn(String dn) {
        return dn.toLowerCase(Locale.ROOT).replaceAll("\\s*([,=])\\s*", "$1").trim();
    }

    private static String hex(byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (byte b : v) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] utf8(char[] password) {
        ByteBuffer bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password == null ? new char[0] : password));
        return Arrays.copyOf(bb.array(), bb.limit());
    }

    /** Checks the server certificate, asking the user about ones the system does not trust. */
    private static javax.net.ssl.SSLSocketFactory sslFactory(ConnectionSettings s) throws LDAPException {
        try {
            return CertificateTrust.socketFactory(s.host(), s.port());
        } catch (GeneralSecurityException e) {
            throw new LDAPException("TLS setup failed: " + e.getMessage(), LDAPException.CONNECT_ERROR, e.getMessage());
        }
    }

    static void safeDisconnect(LDAPConnection c) {
        try {
            if (c != null && c.isConnected()) {
                c.disconnect();
            }
        } catch (LDAPException ignored) {
        }
    }

    @Override
    public void close() {
        safeDisconnect(conn);
    }
}
