package com.pointbluetech.dirxml.trace.ldap;

/**
 * An eDirectory server listed in a driver set's {@code DirXML-ServerList}.
 *
 * @param dn   the NCP Server object's DN
 * @param name its CN, used as the display name and to tag trace events
 * @param host address to open LDAP connections to, or null if it could not be determined
 */
public record ServerInfo(String dn, String name, String host) {

    @Override
    public String toString() {
        return name;
    }
}
