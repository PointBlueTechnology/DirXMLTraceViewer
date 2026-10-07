package com.pointbluetech.dirxml.trace.ldap;

import com.novell.ldap.LDAPException;
import com.pointbluetech.dirxml.trace.model.RawTraceEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Identity Vault as seen from every server in the discovered driver sets. Driver state and trace
 * level are per-server, and each server only emits trace for the drivers it runs, so this keeps one
 * query connection and one trace stream per server. Methods may block on the network; call them off
 * the event thread. Not thread-safe for concurrent callers.
 */
public final class VaultConnection implements AutoCloseable {

    private final ConnectionSettings settings;
    private final LdapSession primary;
    private final Map<String, LdapSession> sessions = new LinkedHashMap<>();
    private final Map<String, String> serverErrors = new LinkedHashMap<>();
    private final List<TraceEventSource> streams = new ArrayList<>();
    private List<DirXmlObject> driverSets = List.of();

    private VaultConnection(ConnectionSettings settings, LdapSession primary) {
        this.settings = settings;
        this.primary = primary;
    }

    public static VaultConnection open(ConnectionSettings settings) throws LDAPException {
        return new VaultConnection(settings, LdapSession.open(settings));
    }

    public ConnectionSettings settings() {
        return settings;
    }

    /** Discovers driver sets, connects to each of their servers, and reads every status. */
    public List<DirXmlObject> discover() throws LDAPException {
        driverSets = primary.discover();
        String primaryDn = primary.serverDn();
        for (ServerInfo s : servers()) {
            if (primaryDn != null && LdapSession.sameDn(s.dn(), primaryDn)) {
                sessions.put(s.dn(), primary);
                continue;
            }
            try {
                if (s.host() == null) throw new IllegalStateException("no network address");
                sessions.put(s.dn(), LdapSession.open(settings.withHost(s.host())));
            } catch (LDAPException e) {
                serverErrors.put(s.dn(), "cannot connect to " + s.host() + ": " + LdapSession.describe(e));
            } catch (RuntimeException e) {
                serverErrors.put(s.dn(), e.getMessage());
            }
        }
        List<DirXmlObject> refreshed = new ArrayList<>();
        for (DirXmlObject set : driverSets) {
            refreshed.add(refreshAll(set));
        }
        driverSets = refreshed;
        return driverSets;
    }

    /** Every distinct server across the discovered driver sets. */
    public List<ServerInfo> servers() {
        Map<String, ServerInfo> all = new LinkedHashMap<>();
        driverSets.forEach(set -> set.servers().forEach(s -> all.putIfAbsent(s.dn(), s)));
        return List.copyOf(all.values());
    }

    /**
     * Starts a trace stream from each reachable server. Events are tagged with the server name.
     * Returns the servers whose stream could not be started, with the reason.
     */
    public Map<String, String> startTrace(Consumer<RawTraceEvent> onEvent, Consumer<Exception> onError) {
        Map<String, String> failures = new LinkedHashMap<>();
        List<ServerInfo> targets = servers();
        if (targets.isEmpty()) {
            // No server list (or no driver sets found): trace whatever the connected server emits.
            targets = List.of(new ServerInfo("", settings.host(), settings.host()));
        }
        for (ServerInfo s : targets) {
            if (serverErrors.containsKey(s.dn())) {
                failures.put(s.name(), serverErrors.get(s.dn()));
                continue;
            }
            LdapTraceEventSource src = new LdapTraceEventSource(settings.withHost(s.host()), s.name());
            try {
                src.start(onEvent, onError);
                streams.add(src);
            } catch (LDAPException e) {
                failures.put(s.name(), LdapSession.describe(e));
            }
        }
        return failures;
    }

    /** Re-reads a driver set and all its drivers from every server. */
    public DirXmlObject refreshAll(DirXmlObject set) {
        List<DirXmlObject> drivers = new ArrayList<>();
        for (DirXmlObject d : set.drivers()) {
            drivers.add(refresh(d, set.servers()));
        }
        return refresh(set, set.servers()).withDrivers(drivers);
    }

    /** Re-reads one object's state and trace level from each of the given servers. */
    public DirXmlObject refresh(DirXmlObject o, List<ServerInfo> servers) {
        List<DriverStatus> statuses = new ArrayList<>();
        for (ServerInfo s : servers) {
            LdapSession session = sessions.get(s.dn());
            statuses.add(session == null ? DriverStatus.failed(s, serverErrors.getOrDefault(s.dn(), "not connected"))
                    : session.readStatus(o, s));
        }
        return o.withStatuses(statuses);
    }

    public void setTraceLevel(DirXmlObject o, ServerInfo server, int level) throws LDAPException {
        session(server).setTraceLevel(o, level);
    }

    public void startDriver(DirXmlObject driver, ServerInfo server) throws LDAPException {
        session(server).startDriver(driver.dn());
    }

    public void stopDriver(DirXmlObject driver, ServerInfo server) throws LDAPException {
        session(server).stopDriver(driver.dn());
    }

    public void restartDriver(DirXmlObject driver, ServerInfo server) throws LDAPException {
        session(server).restartDriver(driver.dn());
    }

    private LdapSession session(ServerInfo server) throws LDAPException {
        LdapSession s = sessions.get(server.dn());
        if (s == null) {
            String why = serverErrors.getOrDefault(server.dn(), "not connected");
            throw new LDAPException(server.name() + ": " + why, LDAPException.CONNECT_ERROR, why);
        }
        return s;
    }

    @Override
    public void close() {
        streams.forEach(TraceEventSource::close);
        streams.clear();
        sessions.values().forEach(LdapSession::close);
        sessions.clear();
        primary.close();
    }
}
