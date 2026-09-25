package com.pointbluetech.dirxml.trace.ldap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LdapSessionTest {

    private static byte[] netAddress(String type, int... bytes) {
        byte[] prefix = (type + "#").getBytes(StandardCharsets.US_ASCII);
        byte[] v = new byte[prefix.length + bytes.length];
        System.arraycopy(prefix, 0, v, 0, prefix.length);
        for (int i = 0; i < bytes.length; i++) v[prefix.length + i] = (byte) bytes[i];
        return v;
    }

    @Test
    void tcpAndIpAddressesYieldIpv4() {
        // 2-byte port (524) then 192.0.2.10
        assertEquals("192.0.2.10", LdapSession.ipv4FromNetworkAddress(netAddress("9", 0x02, 0x0c, 192, 0, 2, 10)));
        assertEquals("192.0.2.10", LdapSession.ipv4FromNetworkAddress(netAddress("1", 0x02, 0x0c, 192, 0, 2, 10)));
    }

    @Test
    void otherTypesAndShortValuesAreIgnored() {
        assertNull(LdapSession.ipv4FromNetworkAddress(netAddress("0", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));
        assertNull(LdapSession.ipv4FromNetworkAddress(netAddress("9", 1, 2, 3)));
        assertNull(LdapSession.ipv4FromNetworkAddress("garbage".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void dnComparisonIgnoresCaseAndSpacing() {
        assertEquals(true, LdapSession.sameDn("CN=IDM-IG4, ou=servers,o=system", "cn=idm-ig4,ou=servers,o=system"));
    }
}
