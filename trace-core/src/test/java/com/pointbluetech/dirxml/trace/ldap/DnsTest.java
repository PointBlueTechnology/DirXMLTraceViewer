package com.pointbluetech.dirxml.trace.ldap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #24: DNs are read with their escapes, not split on commas and equals signs. */
class DnsTest {

    @Test
    void theFirstValueKeepsEscapedCommasAndEqualsSigns() {
        assertEquals("Smith, John", Dns.firstValue("cn=Smith\\, John,ou=servers,o=system"));
        assertEquals("a=b", Dns.firstValue("cn=a\\=b,o=system"));
        assertEquals("IDM-IG4", Dns.firstValue("CN=IDM-IG4, ou=servers,o=system"));
        assertEquals("server1", Dns.firstValue("cn=server1"));
    }

    @Test
    void hexEscapesAreUtf8AndQuotesAreRemoved() {
        assertEquals("café", Dns.firstValue("cn=caf\\C3\\A9,o=x"));
        assertEquals("Smith, John", Dns.firstValue("cn=\"Smith, John\",o=x"));
        assertEquals("back\\slash", Dns.firstValue("cn=back\\\\slash,o=x"));
    }

    @Test
    void aMultiValuedRdnGivesItsFirstValue() {
        assertEquals("a", Dns.firstValue("cn=a+uid=b,o=x"));
        assertEquals("a+b", Dns.firstValue("cn=a\\+b,o=x"));
    }

    @Test
    void notADnIsReadAsOneValue() {
        assertEquals("server1", Dns.firstValue("server1"));
        assertEquals("", Dns.firstValue(""));
        assertEquals("trailing\\", Dns.firstValue("cn=trailing\\"));
    }

    @Test
    void rdnsSplitOnlyAtUnescapedUnquotedCommas() {
        assertEquals(List.of("cn=Smith\\, John", "ou=servers", "o=system"), Dns.rdns("cn=Smith\\, John, ou=servers,o=system"));
        assertEquals(List.of("cn=\"a,b\"", "o=x"), Dns.rdns("cn=\"a,b\",o=x"));
    }

    @Test
    void sameIgnoresCaseSpacingAndHowAValueIsEscaped() {
        assertTrue(Dns.same("CN=IDM-IG4, ou=servers,o=system", "cn=idm-ig4,ou=servers,o=system"));
        assertTrue(Dns.same("cn=a , o = x", "cn=a,o=x"));
        assertTrue(Dns.same("cn=Smith\\, John,o=x", "cn=\"smith, john\",o=x"));
        assertTrue(Dns.same("cn=caf\\C3\\A9,o=x", "cn=café,o=x"));
        assertTrue(Dns.same("cn=a+uid=b,o=x", "uid=b+cn=a,o=x"));
        assertFalse(Dns.same("cn=Smith\\, John,o=x", "cn=Smith,o=x"));
        assertFalse(Dns.same("cn=a\\,o=x", "cn=a,o=x"), "an escaped comma is not two RDNs");
    }
}
