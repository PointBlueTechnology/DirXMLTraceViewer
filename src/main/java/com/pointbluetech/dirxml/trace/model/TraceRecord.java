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

    /** Character ranges of the header parts; {@code threadStart} is -1 when there is no ST/PT tag. */
    public record Header(int timestampStart, int timestampEnd, int nameStart, int nameEnd,
                         int threadStart, int threadEnd, int end) {
    }
}
