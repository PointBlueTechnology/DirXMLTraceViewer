package com.pointbluetech.dirxml.trace.ldap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DN strings read the way LDAP writes them (RFC 4514): a comma, plus sign or equals sign inside a value is
 * escaped ({@code cn=Smith\, John}) or quoted, and {@code \HH} is a byte of the value's UTF-8. Splitting
 * on the characters themselves gets such names wrong (#24). Lenient: a string that isn't a DN is read as
 * one value, never refused.
 */
final class Dns {

    private Dns() {
    }

    /** The value of the first attribute of the first RDN, unescaped ({@code Smith, John}); the whole string, unescaped, without an {@code =}. */
    static String firstValue(String dn) {
        List<String> avas = avas(rdns(dn).get(0));
        String ava = avas.get(0);
        int eq = unescapedIndex(ava, '=');
        return unescape(eq < 0 ? ava : ava.substring(eq + 1));
    }

    /** The same entry: the same RDNs in order, each the same attributes and values, ignoring case and spacing. */
    static boolean same(String a, String b) {
        return normalized(a).equals(normalized(b));
    }

    private static List<List<String>> normalized(String dn) {
        List<List<String>> out = new ArrayList<>();
        for (String rdn : rdns(dn)) {
            List<String> avas = new ArrayList<>();
            for (String ava : avas(rdn)) {
                int eq = unescapedIndex(ava, '=');
                String type = eq < 0 ? "" : ava.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = unescape(eq < 0 ? ava : ava.substring(eq + 1)).toLowerCase(Locale.ROOT);
                avas.add(type + "=" + value);
            }
            avas.sort(null);   // a multi-valued RDN's order doesn't matter
            out.add(avas);
        }
        return out;
    }

    /** The RDNs, as written (escapes kept), split at commas (or semicolons) that are neither escaped nor quoted. */
    static List<String> rdns(String dn) {
        return split(dn == null ? "" : dn, ',', ';');
    }

    /** A multi-valued RDN's attributes ({@code cn=a+uid=b}), split at plus signs that are neither escaped nor quoted. */
    private static List<String> avas(String rdn) {
        return split(rdn, '+', '+');
    }

    private static List<String> split(String s, char sep, char altSep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                cur.append(c).append(s.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if ((c == sep || c == altSep) && !quoted) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static int unescapedIndex(String s, char c) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (ch == c && !quoted) {
                return i;
            }
        }
        return -1;
    }

    /** A value with its quotes removed and its escapes undone; {@code \HH} pairs are UTF-8 bytes. */
    static String unescape(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && isHex(v, i + 1) && isHex(v, i + 2)) {
                bytes.write(Integer.parseInt(v.substring(i + 1, i + 3), 16));
                i += 2;
                continue;
            }
            flush(bytes, out);
            if (c == '\\' && i + 1 < v.length()) {
                out.append(v.charAt(++i));
            } else {
                out.append(c);
            }
        }
        flush(bytes, out);
        return out.toString();
    }

    private static boolean isHex(String s, int i) {
        return i < s.length() && Character.digit(s.charAt(i), 16) >= 0;
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() > 0) {
            out.append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            bytes.reset();
        }
    }
}
