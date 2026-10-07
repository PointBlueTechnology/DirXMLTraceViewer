package com.pointbluetech.dirxml.trace.model;

import java.util.List;

/**
 * One formatted trace message, attributed to a driver and channel.
 * <p>
 * DirXML messages begin with a header such as {@code [09/25/26 10:15:12.345]:AD ST:}. Events that
 * arrive without a header (typically the body of an XML document) are continuations and inherit the
 * driver and channel of the last header seen.
 *
 * @param server      name of the server that emitted it ("" for a single source)
 * @param text        the message text, always ending with a newline
 * @param colorSpans  DSTrace console color regions within {@code text}
 * @param driverName  driver (trace) name from the header, or null for non-driver messages
 * @param channel     channel the message belongs to
 * @param header      position of the header parts within {@code text}, or null for continuations
 */
public record TraceRecord(String server, long receivedAt, int eventType, String perpetratorDN, String text,
                          List<FormattedText.ColorSpan> colorSpans, String driverName, Channel channel,
                          Header header) {

    /**
     * Character ranges of the header parts; {@code threadStart} is -1 when there is no ST/PT tag. The
     * timestamp range includes its brackets. {@code stamped} is true when the viewer added the
     * timestamp from {@code receivedAt}, so it may be rewritten in another {@link TimestampFormat}.
     */
    public record Header(int timestampStart, int timestampEnd, int nameStart, int nameEnd,
                         int threadStart, int threadEnd, int end, boolean stamped) {
    }

    /**
     * This record with a viewer-added timestamp written in {@code format}; unchanged if the
     * timestamp came with the message or is already in that format.
     */
    public TraceRecord withTimestampFormat(TimestampFormat format) {
        Header h = header;
        if (h == null || !h.stamped()) {
            return this;
        }
        String stamp = format.format(receivedAt);
        int from = h.timestampStart() + 1, to = h.timestampEnd() - 1;
        if (stamp.equals(text.substring(from, to))) {
            return this;
        }
        int shift = stamp.length() - (to - from);
        String newText = text.substring(0, from) + stamp + text.substring(to);
        List<FormattedText.ColorSpan> spans = colorSpans.stream()
                .map(c -> new FormattedText.ColorSpan(move(c.start(), to, shift), move(c.end(), to, shift), c.color()))
                .toList();
        Header moved = new Header(h.timestampStart(), h.timestampEnd() + shift, h.nameStart() + shift,
                h.nameEnd() + shift, h.threadStart() < 0 ? -1 : h.threadStart() + shift,
                h.threadEnd() < 0 ? -1 : h.threadEnd() + shift, h.end() + shift, true);
        return new TraceRecord(server, receivedAt, eventType, perpetratorDN, newText, spans, driverName, channel, moved);
    }

    private static int move(int pos, int after, int shift) {
        return pos >= after ? pos + shift : pos;
    }
}
