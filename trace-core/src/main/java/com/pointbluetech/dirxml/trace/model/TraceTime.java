package com.pointbluetech.dirxml.trace.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time of day in a trace, for "go to time": read from a message's header timestamp, which every
 * format the engine and the viewers write carries as {@code H:mm:ss} with optional milliseconds,
 * whatever the date part looks like. A trace file has no other clock, so this works the same for a
 * file and a live stream.
 */
public final class TraceTime {

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,3}))?)?");

    private TraceTime() {
    }

    /** Milliseconds since midnight from the record's header timestamp, or -1 without one. */
    public static long timeOfDay(TraceRecord record) {
        TraceRecord.Header h = record.header();
        if (h == null) {
            return -1;
        }
        String text = record.text();
        return parse(text.substring(Math.max(0, h.timestampStart()), Math.min(text.length(), h.timestampEnd())));
    }

    /**
     * Milliseconds since midnight in {@code s} ({@code 10:15}, {@code 10:15:12}, {@code 10:15:12.345},
     * or a whole timestamp containing one), or -1 when there is no valid time in it.
     */
    public static long parse(String s) {
        Matcher m = TIME.matcher(s);
        while (m.find()) {
            int hh = Integer.parseInt(m.group(1));
            int mm = Integer.parseInt(m.group(2));
            int ss = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            String frac = m.group(4);
            int ms = frac == null ? 0 : Integer.parseInt((frac + "00").substring(0, 3));
            if (hh < 24 && mm < 60 && ss < 60) {
                return ((hh * 60L + mm) * 60L + ss) * 1000L + ms;
            }
        }
        return -1;
    }
}
