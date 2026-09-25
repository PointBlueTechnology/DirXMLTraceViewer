package com.pointbluetech.dirxml.trace;

import com.pointbluetech.dirxml.trace.ldap.ConnectionSettings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the command line asked for at startup: a trace file to open, a vault to connect to with a
 * driver to select, the demo, or nothing (the Connect dialog). Built so another tool — DirXMLDev's
 * {@code driver.trace view} — can launch the viewer already connected or already showing a file.
 * The bind password is never an argument: it comes from {@code DIRXML_TRACE_VIEWER_PASSWORD} or,
 * with {@code --password-stdin}, from the first line of standard input.
 */
public record StartupOptions(boolean demo, Path openFile, ConnectionSettings settings, String driver) {

    public static final String PASSWORD_ENV = "DIRXML_TRACE_VIEWER_PASSWORD";

    public static final String USAGE = """
        DirXML Trace Viewer
          (no arguments)                       open the Connect dialog
          --open FILE                          open a trace file
          --connect URL|HOST[:PORT] --bind-dn DN [--search-base BASE] [--driver NAME]
                                               connect to an Identity Vault and stream trace; the password
                                               comes from DIRXML_TRACE_VIEWER_PASSWORD, or from the first line
                                               of standard input with --password-stdin. URL is ldaps://host:636
                                               or ldap://host:389; a bare HOST means LDAPS on 636.
          --no-legacy-ciphers                  do not enable static-RSA TLS suites for this connection
          --demo                               stream synthetic trace, no vault
          --help                               this text
        """;

    public static StartupOptions dialog() {
        return new StartupOptions(false, null, null, null);
    }

    public static StartupOptions demoMode() {
        return new StartupOptions(true, null, null, null);
    }

    /** Parse {@code args}; {@code env} is the process environment and {@code stdin} reads one line when asked. */
    public static StartupOptions parse(String[] args, Map<String, String> env, Supplier<String> stdin) {
        boolean demo = false;
        Path open = null;
        String connect = null;
        String bindDn = null;
        String searchBase = "";
        String driver = null;
        boolean passwordStdin = false;
        boolean legacy = true;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--demo" -> demo = true;
                case "--help", "-h" -> throw new IllegalArgumentException(USAGE);
                case "--open" -> open = Paths.get(value(args, ++i, a));
                case "--connect" -> connect = value(args, ++i, a);
                case "--bind-dn" -> bindDn = value(args, ++i, a);
                case "--search-base" -> searchBase = value(args, ++i, a);
                case "--driver" -> driver = value(args, ++i, a);
                case "--password-stdin" -> passwordStdin = true;
                case "--no-legacy-ciphers" -> legacy = false;
                default -> throw new IllegalArgumentException("unknown argument " + a + "\n" + USAGE);
            }
        }
        if (open != null && connect != null) {
            throw new IllegalArgumentException("--open and --connect exclude each other\n" + USAGE);
        }
        if (open != null) {
            if (!Files.isRegularFile(open)) {
                throw new IllegalArgumentException("--open: no such file " + open);
            }
            return new StartupOptions(false, open, null, null);
        }
        if (connect == null) {
            if (bindDn != null || driver != null || passwordStdin) {
                throw new IllegalArgumentException("--bind-dn, --driver and --password-stdin need --connect\n" + USAGE);
            }
            return demo ? demoMode() : dialog();
        }
        if (bindDn == null || bindDn.isBlank()) {
            throw new IllegalArgumentException("--connect needs --bind-dn\n" + USAGE);
        }
        String password = passwordStdin ? stdin.get() : env.get(PASSWORD_ENV);
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("--connect: no password — set " + PASSWORD_ENV + " or give --password-stdin");
        }
        String u = connect.trim();
        boolean ssl = !u.toLowerCase().startsWith("ldap://");
        String hostPort = u.replaceFirst("(?i)^ldaps?://", "").replaceAll("/.*$", "");
        int colon = hostPort.lastIndexOf(':');
        String host;
        int port;
        if (colon > 0 && hostPort.indexOf(']') < colon) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        } else {
            host = hostPort;
            port = ssl ? 636 : 389;
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("--connect: no host in " + connect);
        }
        ConnectionSettings s = new ConnectionSettings(host, port, ssl, legacy, bindDn.trim(), password.toCharArray(), searchBase.trim());
        return new StartupOptions(false, null, s, driver);
    }

    /** One line from standard input, for {@code --password-stdin}. */
    public static String readStdinLine() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = r.readLine();
            return line == null ? null : line.strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value\n" + USAGE);
        }
        return args[i];
    }
}
