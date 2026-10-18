package com.pointbluetech.dirxml.trace.model;

import java.util.regex.Pattern;

/**
 * The places in a trace worth jumping to (issue #2): where a driver starts, where an event enters
 * on either channel, where a policy set or a policy is applied, where a transaction ends, and so
 * on. Each kind is recognised by the engine's own wording on the first line of a message, after
 * its header. Both viewers jump between the records of one kind: next, previous, most recent.
 */
public enum TraceMarker {

    DRIVER_START("Driver start", "Reading driver information"),
    PUBLISHER_EVENT("Publisher event [p/P]", "Receiving DOM document from application"),
    SUBSCRIBER_EVENT("Subscriber event [s/S]", "Start transaction"),
    SUBSCRIBER_EXECUTE("Subscriber shim  [x/X]", "Submitting document to subscriber shim"),
    POLICY_SET("Policy set [m/M]", "Applying [^\\r\\n]*? policies"),
    POLICY("Policy [n/N]", "Applying policy:"),
    POLICY_RESULT("Policy result [r/R]", "Query from policy result"),
    END_TRANSACTION("End transaction [t/T]", "End transaction"),
    DIRXML_LOG_EVENT("Log event [l/L]", "DirXML Log Event"),
    PUMPING_XDS("Pumping XDS to eDirectory [e/E]", "Pumping XDS to eDirectory");

    private final String label;
    private final Pattern pattern;

    TraceMarker(String label, String regex) {
        this.label = label;
        this.pattern = Pattern.compile(regex);
    }

    /** The name a viewer shows for this kind. */
    public String label() {
        return label;
    }

    /**
     * The kind of place this record is, or null. A policy counts only on the subscriber or
     * publisher channel ({@code ST:} / {@code PT:}), as the engine traces one.
     */
    public static TraceMarker of(TraceRecord record) {
        String text = record.text();
        TraceRecord.Header h = record.header();
        int from = h == null ? 0 : Math.min(h.end(), text.length());
        int nl = text.indexOf('\n', from);
        String first = text.substring(from, nl < 0 ? text.length() : nl);
        // Special case for DirXML Log Event: it follows a new line so use whole text to match
        if (first.length() == 0 && TraceMarker.DIRXML_LOG_EVENT.pattern.matcher(text).find()) {
           return DIRXML_LOG_EVENT;
        }
        for (TraceMarker m : values()) {
            if (m.pattern.matcher(first).find()) {
                if (m == POLICY && (h == null || h.threadStart() < 0
                        || (record.channel() != Channel.SUBSCRIBER && record.channel() != Channel.PUBLISHER))) {
                    continue;
                }
                return m;
            }
        }
        return null;
    }
}
