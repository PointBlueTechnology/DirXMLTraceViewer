package com.pointbluetech.dirxml.trace.ldap;

import java.util.List;

/**
 * A driver set or driver discovered in the Identity Vault.
 *
 * @param traceName the name the engine prints in trace headers (the trace name if configured, else the CN)
 * @param servers   for a driver set, the servers in its {@code DirXML-ServerList}; empty for a driver
 * @param statuses  state and trace level per server, in the driver set's server order
 * @param drivers   the drivers of a driver set; empty for a driver
 */
public record DirXmlObject(Kind kind, String dn, String name, String traceName, List<ServerInfo> servers,
                           List<DriverStatus> statuses, List<DirXmlObject> drivers) {

    public enum Kind { DRIVER_SET, DRIVER }

    public DirXmlObject {
        servers = List.copyOf(servers);
        statuses = List.copyOf(statuses);
        drivers = List.copyOf(drivers);
    }

    public DirXmlObject withStatuses(List<DriverStatus> s) {
        return new DirXmlObject(kind, dn, name, traceName, servers, s, drivers);
    }

    public DirXmlObject withDrivers(List<DirXmlObject> d) {
        return new DirXmlObject(kind, dn, name, traceName, servers, statuses, d);
    }

    /** The trace-level attribute: driver sets and drivers keep theirs under different names. */
    public String traceLevelAttribute() {
        return kind == Kind.DRIVER_SET ? "DirXML-DriverTraceLevel" : "DirXML-TraceLevel";
    }

    @Override
    public String toString() {
        return name;
    }
}
