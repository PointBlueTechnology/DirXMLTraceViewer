package com.pointbluetech.dirxml.trace.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes whitespace-only lines from a record for display. Some engine output (notably XSLT policy
 * trace) separates every step with several blank lines. Color spans and header positions are
 * remapped so highlighting still lines up.
 */
public final class TraceCompactor {

    private TraceCompactor() {
    }

    public static TraceRecord compact(TraceRecord r) {
        String text = r.text();
        StringBuilder out = new StringBuilder(text.length());
        // map[i] = position in the output of input char i (or where it would have been, if removed)
        int[] map = new int[text.length() + 1];
        int lineStart = 0;
        boolean removedAny = false;
        while (lineStart < text.length()) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? text.length() : nl + 1;
            boolean blank = text.substring(lineStart, lineEnd).isBlank();
            // Keep the first line even if blank so a record never becomes empty or loses its header.
            if (blank && lineStart > 0) {
                for (int i = lineStart; i < lineEnd; i++) {
                    map[i] = out.length();
                }
                removedAny = true;
            } else {
                for (int i = lineStart; i < lineEnd; i++) {
                    map[i] = out.length();
                    out.append(text.charAt(i));
                }
            }
            lineStart = lineEnd;
        }
        map[text.length()] = out.length();
        if (!removedAny) {
            return r;
        }

        List<FormattedText.ColorSpan> spans = new ArrayList<>();
        for (FormattedText.ColorSpan c : r.colorSpans()) {
            int start = map[c.start()];
            int end = map[c.end()];
            if (end > start) {
                spans.add(new FormattedText.ColorSpan(start, end, c.color()));
            }
        }
        TraceRecord.Header h = r.header();
        TraceRecord.Header header = h == null ? null : new TraceRecord.Header(map[h.timestampStart()],
                map[h.timestampEnd()], map[h.nameStart()], map[h.nameEnd()],
                h.threadStart() < 0 ? -1 : map[h.threadStart()], h.threadEnd() < 0 ? -1 : map[h.threadEnd()],
                map[h.end()]);
        return new TraceRecord(r.server(), r.receivedAt(), r.eventType(), r.perpetratorDN(), out.toString(), spans,
                r.driverName(), r.channel(), header);
    }
}
