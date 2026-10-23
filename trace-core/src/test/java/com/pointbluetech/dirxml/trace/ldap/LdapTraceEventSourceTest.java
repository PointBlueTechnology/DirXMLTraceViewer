package com.pointbluetech.dirxml.trace.ldap;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A live event's time is the server's: its dsTime and milliseconds, the local clock only when it sends none. */
class LdapTraceEventSourceTest {

    @Test
    void usesTheServersSecondsAndMilliseconds() {
        // seen on an eDirectory 9.2 lab: dsTime 1792780825, milliSeconds 419
        assertEquals(Instant.parse("2026-10-23T18:40:25.419Z").toEpochMilli(),
                LdapTraceEventSource.eventTime(1792780825, 419, 0));
    }

    @Test
    void fallsBackToTheLocalClockWhenTheServerSendsNoTime() {
        assertEquals(123_456L, LdapTraceEventSource.eventTime(0, 0, 123_456L));
    }

    @Test
    void readsDsTimeAsUnsignedSoItKeepsWorkingAfter2038() {
        int after2038 = (int) Instant.parse("2040-01-01T00:00:00Z").getEpochSecond();   // negative as an int
        assertEquals(Instant.parse("2040-01-01T00:00:00.007Z").toEpochMilli(),
                LdapTraceEventSource.eventTime(after2038, 7, 0));
    }
}
