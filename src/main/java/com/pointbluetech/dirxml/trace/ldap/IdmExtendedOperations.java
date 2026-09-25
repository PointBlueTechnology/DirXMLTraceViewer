package com.pointbluetech.dirxml.trace.ldap;

import com.novell.ldap.LDAPExtendedOperation;
import com.novell.ldap.asn1.ASN1Integer;
import com.novell.ldap.asn1.ASN1Object;
import com.novell.ldap.asn1.ASN1OctetString;
import com.novell.ldap.asn1.ASN1Sequence;
import com.novell.ldap.asn1.LBERDecoder;
import com.novell.ldap.asn1.LBEREncoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The IDM engine's LDAP extended operations for driver state and start/stop/restart, encoded with
 * JLDAP's BER classes so no IDM libraries are needed. Each request's value is the driver DN as a
 * BER OCTET STRING; the get-state reply is a BER SEQUENCE whose first element is the state as an
 * INTEGER.
 */
final class IdmExtendedOperations {

    private static final String OID_BASE = "2.16.840.1.113719.1.14.100.";
    static final String GET_DRIVER_STATE = OID_BASE + "13";
    static final String START_DRIVER = OID_BASE + "15";
    static final String STOP_DRIVER = OID_BASE + "17";
    static final String RESTART_DRIVER = OID_BASE + "101";

    private IdmExtendedOperations() {
    }

    static LDAPExtendedOperation request(String oid, String driverDn) {
        return new LDAPExtendedOperation(oid, encodeDn(driverDn));
    }

    static byte[] encodeDn(String dn) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new ASN1OctetString(dn).encode(new LBEREncoder(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e); // cannot happen writing to memory
        }
        return out.toByteArray();
    }

    /** Decodes a get-driver-state reply value; returns {@link DriverStatus#UNKNOWN} if it is not as expected. */
    static int decodeDriverState(byte[] value) {
        if (value == null || value.length == 0) {
            return DriverStatus.UNKNOWN;
        }
        try {
            ASN1Object decoded = new LBERDecoder().decode(value);
            if (decoded instanceof ASN1Sequence seq && seq.size() > 0 && seq.get(0) instanceof ASN1Integer state) {
                return state.intValue();
            }
        } catch (RuntimeException ignored) {
            // malformed reply
        }
        return DriverStatus.UNKNOWN;
    }
}
