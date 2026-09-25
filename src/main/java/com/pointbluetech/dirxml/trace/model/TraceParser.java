package com.pointbluetech.dirxml.trace.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw DSTrace events into {@link TraceRecord}s. Stateful: continuation events are attributed
 * to the driver/channel of the most recent header from the same server, since streams from several
 * servers interleave. Not thread-safe; feed it from one thread.
 */
public final class TraceParser {

    /**
     * {@code [timestamp]:<driver name> <channel tag>:} at the start of a message, as in trace files.
     * The tag is {@code ST}/{@code PT} for the subscriber/publisher and blank for driver-level
     * messages; other short tags (e.g. a service channel) are accepted and treated as "other".
     */
    private static final Pattern HEADER = Pattern.compile("^\\s*\\[([^\\]\\r\\n]{6,40})\\]:([^:\\r\\n]*?)(?: ([A-Z]{2,3}))?:");
    /**
     * The same header as delivered by LDAP events, without the timestamp (the DSTrace console adds
     * it). The space before the tag is always present, so {@code "AD :"} has a blank tag.
     */
    private static final Pattern UNSTAMPED_HEADER = Pattern.compile("^[^:\\[\\s<][^:\\r\\n]*? (?:[A-Z]{2,3})?:");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS");

    private static final class State {
        String lastDriver;
        Channel lastChannel = Channel.OTHER;
    }

    private final Map<String, State> states = new HashMap<>();

    public TraceRecord parse(RawTraceEvent event) {
        FormattedText formatted = DsTraceFormatter.format(event.formatString(), event.parameters());
        String text = formatted.text();
        List<FormattedText.ColorSpan> spans = formatted.colorSpans();
        if (UNSTAMPED_HEADER.matcher(text).find()) {
            String stamp = "[" + STAMP.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(event.receivedAt()),
                    ZoneId.systemDefault())) + "]:";
            text = stamp + text;
            int shift = stamp.length();
            spans = spans.stream()
                    .map(c -> new FormattedText.ColorSpan(c.start() + shift, c.end() + shift, c.color()))
                    .toList();
        }
        return attribute(event.server(), event.receivedAt(), event.eventType(), event.perpetratorDN(), text, spans);
    }

    /**
     * Parses text that is already formatted, e.g. a message read from an on-server trace file.
     * Unlike {@link #parse} it does not interpret DSTrace {@code %} directives.
     */
    public TraceRecord parseText(String source, String text) {
        return attribute(source, 0, 0, "", text, List.of());
    }

    /** True if a line begins a new message in a trace file ({@code [timestamp]:name TAG:}). */
    public static boolean isHeaderLine(String line) {
        return HEADER.matcher(line).find();
    }

    private TraceRecord attribute(String server, long receivedAt, int eventType, String perpetratorDN, String text,
                                  List<FormattedText.ColorSpan> spans) {
        if (!text.endsWith("\n")) {
            text = text + "\n";
        }

        State st = states.computeIfAbsent(server, k -> new State());
        Matcher m = HEADER.matcher(text);
        TraceRecord.Header header = null;
        if (m.find()) {
            String name = m.group(2).trim();
            st.lastDriver = name.isEmpty() ? null : name;
            st.lastChannel = Channel.fromThreadTag(m.group(3));
            header = new TraceRecord.Header(m.start(1) - 1, m.end(1) + 1, m.start(2), m.end(2),
                    m.group(3) == null ? -1 : m.start(3), m.group(3) == null ? -1 : m.end(3), m.end());
        }
        return new TraceRecord(server, receivedAt, eventType, perpetratorDN, text, spans, st.lastDriver,
                st.lastChannel, header);
    }

    public void reset() {
        states.clear();
    }
}
