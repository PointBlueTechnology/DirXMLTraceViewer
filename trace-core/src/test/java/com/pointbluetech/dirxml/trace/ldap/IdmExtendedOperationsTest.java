package com.pointbluetech.dirxml.trace.ldap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the wire format of the IDM driver extended operations. */
class IdmExtendedOperationsTest {

    @Test
    void requestValueIsTheDnAsAnOctetString() {
        String dn = "cn=IG Update,cn=driverset1,o=system";
        byte[] utf8 = dn.getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[2 + utf8.length];
        expected[0] = 0x04; // OCTET STRING
        expected[1] = (byte) utf8.length;
        System.arraycopy(utf8, 0, expected, 2, utf8.length);
        assertArrayEquals(expected, IdmExtendedOperations.encodeDn(dn));
        assertArrayEquals(expected, IdmExtendedOperations.request(IdmExtendedOperations.STOP_DRIVER, dn).getValue());
    }

    @Test
    void oids() {
        assertEquals("2.16.840.1.113719.1.14.100.13", IdmExtendedOperations.GET_DRIVER_STATE);
        assertEquals("2.16.840.1.113719.1.14.100.15", IdmExtendedOperations.START_DRIVER);
        assertEquals("2.16.840.1.113719.1.14.100.17", IdmExtendedOperations.STOP_DRIVER);
        assertEquals("2.16.840.1.113719.1.14.100.101", IdmExtendedOperations.RESTART_DRIVER);
    }

    @Test
    void decodesStateFromSequence() {
        // SEQUENCE { INTEGER 2 }
        assertEquals(DriverStatus.STATE_RUNNING, IdmExtendedOperations.decodeDriverState(new byte[]{0x30, 0x03, 0x02, 0x01, 0x02}));
        assertEquals(DriverStatus.STATE_STOPPED, IdmExtendedOperations.decodeDriverState(new byte[]{0x30, 0x03, 0x02, 0x01, 0x00}));
    }

    @Test
    void unexpectedRepliesAreUnknown() {
        assertEquals(DriverStatus.UNKNOWN, IdmExtendedOperations.decodeDriverState(null));
        assertEquals(DriverStatus.UNKNOWN, IdmExtendedOperations.decodeDriverState(new byte[0]));
        assertEquals(DriverStatus.UNKNOWN, IdmExtendedOperations.decodeDriverState(new byte[]{0x04, 0x01, 0x41}));
        assertEquals(DriverStatus.UNKNOWN, IdmExtendedOperations.decodeDriverState(new byte[]{0x30, 0x00}));
    }
}
