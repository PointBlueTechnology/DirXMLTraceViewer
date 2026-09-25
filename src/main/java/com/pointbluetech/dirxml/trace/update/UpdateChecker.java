package com.pointbluetech.dirxml.trace.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the project's latest release and compares it with the running version. The only
 * data sent is the request itself (with a User-Agent naming the app and version, which GitHub's API
 * requires); no identifiers or usage information.
 */
public final class UpdateChecker {

    /** Public API for the newest non-draft, non-prerelease release. */
    public static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/PointBlueTechnology/DirXMLTraceViewer/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/PointBlueTechnology/DirXMLTraceViewer/releases";

    private static final int TIMEOUT_MS = 5_000;
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern HTML_URL = Pattern.compile("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+/releases/tag/[^\"]+)\"");

    /** The latest published release. */
    public record Release(String version, String tag, String pageUrl) {
    }

    private UpdateChecker() {
    }

    /** Fetches the latest release. The URL can be overridden with {@code -Ddirxml.updateUrl} for testing. */
    public static Release latest(String currentVersion) throws IOException {
        String url = System.getProperty("dirxml.updateUrl", LATEST_RELEASE_URL);
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "DirXML-Trace-Viewer/" + (currentVersion == null ? "dev" : currentVersion));
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub returned HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                return parse(new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    static Release parse(String json) throws IOException {
        Matcher tag = TAG.matcher(json);
        if (!tag.find()) {
            throw new IOException("No release found");
        }
        Matcher page = HTML_URL.matcher(json);
        String t = tag.group(1);
        return new Release(t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t, t,
                page.find() ? page.group(1) : RELEASES_PAGE);
    }

    /**
     * True if {@code candidate} is newer than {@code current}. Versions are dotted numbers with an
     * optional qualifier; a qualified version (e.g. 1.2.0-SNAPSHOT) comes before the plain release.
     */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    static int compare(String a, String b) {
        List<Integer> na = numbers(a), nb = numbers(b);
        for (int i = 0; i < Math.max(na.size(), nb.size()); i++) {
            int x = i < na.size() ? na.get(i) : 0;
            int y = i < nb.size() ? nb.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        boolean qa = hasQualifier(a), qb = hasQualifier(b);
        return qa == qb ? 0 : qa ? -1 : 1;
    }

    private static List<Integer> numbers(String v) {
        List<Integer> out = new ArrayList<>();
        for (String part : strip(v).split("[-+]", 2)[0].split("\\.")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException e) {
                out.add(0);
            }
        }
        return out;
    }

    private static boolean hasQualifier(String v) {
        return strip(v).contains("-");
    }

    private static String strip(String v) {
        String s = v == null ? "" : v.trim();
        return s.startsWith("v") || s.startsWith("V") ? s.substring(1) : s;
    }
}
