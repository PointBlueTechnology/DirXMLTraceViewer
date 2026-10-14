package com.pointbluetech.dirxml.trace.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fold regions in a trace (issue #3, docs/folding.md): policy sets, policies and XML documents.
 * Stateful, like {@link TraceParser}: feed it the records in order with {@link #add} and it says, for
 * each, which regions the record opens, which region it belongs to, and where the XML documents
 * inside its text are. Not thread-safe.
 * <p>
 * A region belongs to one thread (server, driver, channel), since a live trace interleaves them;
 * only that thread's records extend or close it. A region learns where it ends only later, so a
 * viewer counts what it would hide as the records arrive. A policy set holds its policies, a policy
 * its rules and returned document, either one an XML document.
 */
public final class TraceFolds {

    public enum Kind { POLICY_SET, POLICY, XML }

    /**
     * A foldable region: {@code parent} is the region it sits in, or -1; {@code label} names it
     * (the set's message, the policy's name, the document's root element).
     */
    public record Region(int id, Kind kind, int parent, String label) {
    }

    /**
     * Where one record falls. {@code opens}: the regions it starts (its first line stays visible when
     * they fold). {@code within}: the innermost region it belongs to, -1 for none; a region it opens
     * is not counted, so an opener's {@code within} is its region's parent. {@code xmlLines}: for
     * text of several lines, each XML document in it as {first line, last line}, 0-based and
     * inclusive, to fold inside the record.
     */
    public record Placement(List<Region> opens, int within, List<int[]> xmlLines) {
    }

    private static final Pattern POLICY_NAME = Pattern.compile("Applying policy:\\s*(.*?)\\.?\\s*$");
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z_][\\w:.\\-]*)[^<>]*?(/?)>");

    private static final class Thread {
        Region set;
        Region policy;
        boolean returned;   // the policy has traced "Policy returned:"
        Region xml;         // a document arriving as records without a header (a live stream)
    }

    private final Map<String, Thread> threads = new HashMap<>();
    private int next;

    public Placement add(TraceRecord record) {
        Thread t = threads.computeIfAbsent(record.server() + '\u0000' + record.driverName() + '\u0000' + record.channel(), k -> new Thread());
        List<Region> opens = new ArrayList<>(1);
        if (record.header() != null) {
            t.xml = null;   // a new message ends the last one's document
            TraceMarker m = TraceMarker.of(record);
            String body = firstLine(record);
            if (t.policy != null && (t.returned || m == TraceMarker.POLICY || m == TraceMarker.POLICY_SET || boundary(m))) {
                t.policy = null;
                t.returned = false;
            }
            if (t.set != null && (m == TraceMarker.POLICY_SET || boundary(m) || (t.policy == null && m != TraceMarker.POLICY))) {
                t.set = null;
            }
            if (m == TraceMarker.POLICY_SET) {
                t.set = open(opens, Kind.POLICY_SET, -1, body.trim());
            } else if (m == TraceMarker.POLICY) {
                Matcher n = POLICY_NAME.matcher(body);
                t.policy = open(opens, Kind.POLICY, t.set == null ? -1 : t.set.id(), n.find() ? n.group(1) : body.trim());
            } else if (t.policy != null && body.trim().startsWith("Policy returned:")) {
                t.returned = true;
            }
        } else if (t.xml == null && startsXml(record.text())) {
            Region parent = innermost(t, null);
            t.xml = open(opens, Kind.XML, parent == null ? -1 : parent.id(), rootLabel(record.text()));
        }
        Region in = innermost(t, opens);
        return new Placement(opens, in == null ? -1 : in.id(), xmlLines(record.text()));
    }

    /** A place that ends the policy work in progress: a new event, the shim, the end of a transaction, a pump. */
    private static boolean boundary(TraceMarker m) {
        return m != null && m != TraceMarker.POLICY && m != TraceMarker.POLICY_SET && m != TraceMarker.LOG_EVENT;
    }

    private Region open(List<Region> opens, Kind kind, int parent, String label) {
        Region r = new Region(next++, kind, parent, label);
        opens.add(r);
        return r;
    }

    /** The thread's innermost open region that the record did not itself open. */
    private static Region innermost(Thread t, List<Region> opened) {
        for (Region r : new Region[] {t.xml, t.policy, t.set}) {
            if (r != null && (opened == null || !opened.contains(r))) {
                return r;
            }
        }
        return null;
    }

    private static String firstLine(TraceRecord record) {
        String text = record.text();
        int from = record.header() == null ? 0 : Math.min(record.header().end(), text.length());
        int nl = text.indexOf('\n', from);
        return text.substring(from, nl < 0 ? text.length() : nl);
    }

    private static boolean startsXml(String text) {
        String s = text.stripLeading();
        return s.startsWith("<") && !s.startsWith("</") && !s.startsWith("<!--");
    }

    private static String rootLabel(String text) {
        Matcher m = TAG.matcher(text);
        return m.find() && m.group(1).isEmpty() ? "<" + m.group(2) + ">" : "XML";
    }

    /**
     * The XML documents in a record's text, by line: a document starts at a line that begins with an
     * element and runs to its root's end tag (or the end of the text). Only documents of two lines
     * or more are worth folding.
     */
    static List<int[]> xmlLines(String text) {
        String[] lines = text.split("\n", -1);
        int count = text.endsWith("\n") ? lines.length - 1 : lines.length;
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < count) {
            String s = lines[i].stripLeading();
            if (!s.startsWith("<") || s.startsWith("</") || s.startsWith("<?") || s.startsWith("<!")) {
                i++;
                continue;
            }
            int depth = 0;
            int end = count - 1;
            boolean opened = false;
            scan:
            for (int j = i; j < count; j++) {
                Matcher m = TAG.matcher(lines[j]);
                while (m.find()) {
                    if (!m.group(1).isEmpty()) {
                        depth--;
                    } else if (m.group(3).isEmpty()) {
                        depth++;
                        opened = true;
                    } else {
                        opened = true;   // <x/>: opens and closes
                    }
                    if (opened && depth <= 0) {
                        end = j;
                        break scan;
                    }
                }
            }
            if (end > i) {
                out.add(new int[] {i, end});
            }
            i = end + 1;
        }
        return out;
    }
}
