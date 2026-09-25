package com.pointbluetech.dirxml.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StartupOptionsTest {

    @Test
    void nothingMeansTheDialogAndDemoMeansDemo() {
        assertNull(StartupOptions.parse(new String[0], Map.of(), () -> null).settings());
        assertFalse(StartupOptions.parse(new String[0], Map.of(), () -> null).demo());
        assertTrue(StartupOptions.parse(new String[] {"--demo"}, Map.of(), () -> null).demo());
    }

    @Test
    void openNeedsAnExistingFile() throws Exception {
        Path f = Files.createTempFile("trace", ".log");
        StartupOptions o = StartupOptions.parse(new String[] {"--open", f.toString()}, Map.of(), () -> null);
        assertEquals(f, o.openFile());
        assertNull(o.settings());
        Files.delete(f);
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--open", f.toString()}, Map.of(), () -> null));
    }

    @Test
    void connectParsesUrlsAndTakesThePasswordFromTheEnvironmentOrStdin() {
        StartupOptions o = StartupOptions.parse(new String[] {"--connect", "ldaps://idm.example.com:1636", "--bind-dn", "cn=admin,o=system",
            "--search-base", "o=system", "--driver", "AD Driver"}, Map.of(StartupOptions.PASSWORD_ENV, "secret"), () -> null);
        assertEquals("idm.example.com", o.settings().host());
        assertEquals(1636, o.settings().port());
        assertTrue(o.settings().ssl());
        assertTrue(o.settings().legacyCiphers());
        assertEquals("cn=admin,o=system", o.settings().bindDn());
        assertEquals("secret", new String(o.settings().password()));
        assertEquals("o=system", o.settings().searchBase());
        assertEquals("AD Driver", o.driver());

        StartupOptions p = StartupOptions.parse(new String[] {"--connect", "vault", "--bind-dn", "cn=a", "--password-stdin", "--no-legacy-ciphers"},
            Map.of(), () -> "fromstdin");
        assertEquals("vault", p.settings().host());
        assertEquals(636, p.settings().port());
        assertEquals("fromstdin", new String(p.settings().password()));
        assertFalse(p.settings().legacyCiphers());

        StartupOptions q = StartupOptions.parse(new String[] {"--connect", "ldap://10.0.0.5", "--bind-dn", "cn=a"}, Map.of(StartupOptions.PASSWORD_ENV, "x"), () -> null);
        assertEquals(389, q.settings().port());
        assertFalse(q.settings().ssl());
    }

    @Test
    void refusals() {
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--connect", "h"}, Map.of(), () -> null));
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--connect", "h", "--bind-dn", "cn=a"}, Map.of(), () -> null));
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--driver", "x"}, Map.of(), () -> null));
        assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--bogus"}, Map.of(), () -> null));
        IllegalArgumentException help = assertThrows(IllegalArgumentException.class, () -> StartupOptions.parse(new String[] {"--help"}, Map.of(), () -> null));
        assertTrue(help.getMessage().contains("--open FILE"));
    }
}
