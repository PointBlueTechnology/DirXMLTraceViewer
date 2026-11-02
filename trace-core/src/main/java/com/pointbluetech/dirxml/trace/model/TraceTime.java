package com.pointbluetech.dirxml.trace.model;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time of day in a trace, for "go to time": read from a message's header timestamp, which every
 * format the engine and the viewers write carries as {@code H:mm:ss} with optional milliseconds,
 * whatever the date part looks like. A trace file has no other clock, so this works the same for a
 * file and a live stream.
 */
public final class TraceTime {

    private static final long DAY_MILLIS = 24 * 60 * 60 * 1000L;
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,3}))?)?");
    private static final DateTimeFormatter MONTH_SLASH = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY_SLASH = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm:ss.SSS");
    private static final DateTimeFormatter DAY_DOTS = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm:ss.SSS");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");
    /** A slash date's first two fields, which alone cannot say whether the month or the day comes first. */
    private static final Pattern SLASH = Pattern.compile("^(\\d{2})/(\\d{2})/\\d{2} ");
    private static final Pattern DOTS = Pattern.compile("^\\d{2}\\.\\d{2}\\.\\d{2} ");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} ");

    /**
     * Which field of a slash date ({@code 10/11/26}) is the month: U.S. ({@code MM/dd/yy}, the
     * DSTrace console's) or European ({@code dd/MM/yy}). One file uses one order, so it is decided
     * once per file ({@link #dateOrder}), never line by line.
     */
    public enum DateOrder { MONTH_FIRST, DAY_FIRST }

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

    /** {@link #timestamp(TraceRecord, DateOrder)} with U.S. slash dates. */
    public static long timestamp(TraceRecord record) {
        return timestamp(record, DateOrder.MONTH_FIRST);
    }

    /**
     * Timestamp used for display intervals: the event's receive time, or the trace header's date and
     * time in the local time zone (so an interval across a daylight-saving change is right), with
     * slash dates read in {@code order}. A header with a time but no date it can read gives the time
     * of day; no header gives -1.
     */
    public static long timestamp(TraceRecord record, DateOrder order) {
        if (record.receivedAt() != 0) {
            return record.receivedAt();
        }
        TraceRecord.Header header = record.header();
        if (header == null) {
            return -1;
        }
        String stamp = record.text().substring(header.timestampStart() + 1, header.timestampEnd() - 1);
        // the date's shape picks the one formatter to try, so a line costs at most one parse
        DateTimeFormatter formatter = SLASH.matcher(stamp).find() ? (order == DateOrder.DAY_FIRST ? DAY_SLASH : MONTH_SLASH)
                : DOTS.matcher(stamp).find() ? DAY_DOTS
                : ISO_DATE.matcher(stamp).find() ? ISO
                : null;
        if (formatter != null) {
            try {
                return LocalDateTime.parse(stamp, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // a date in an unexpected layout: fall back to the time of day
            }
        }
        return timeOfDay(record);
    }

    /**
     * The order of slash dates in these records: from the first header that only one order can
     * read (a field above 12), else {@code fallback} (the user's chosen format, say).
     */
    public static DateOrder dateOrder(Iterable<TraceRecord> records, DateOrder fallback) {
        for (TraceRecord record : records) {
            TraceRecord.Header header = record.header();
            if (header == null || record.receivedAt() != 0) {
                continue;
            }
            Matcher m = SLASH.matcher(record.text().substring(header.timestampStart() + 1, header.timestampEnd() - 1));
            if (!m.find()) {
                continue;
            }
            int first = Integer.parseInt(m.group(1)), second = Integer.parseInt(m.group(2));
            if (first > 12 && second <= 12) {
                return DateOrder.DAY_FIRST;
            }
            if (second > 12 && first <= 12) {
                return DateOrder.MONTH_FIRST;
            }
        }
        return fallback;
    }

    /** Difference between two display timestamps, accounting for midnight in time-of-day values. */
    public static long deltaMillis(long current, long previous) {
        long delta = current - previous;
        if (current >= 0 && current < DAY_MILLIS && previous >= 0 && previous < DAY_MILLIS && delta < 0) {
            delta += DAY_MILLIS;
        }
        return delta;
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
