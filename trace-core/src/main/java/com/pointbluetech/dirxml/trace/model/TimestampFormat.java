package com.pointbluetech.dirxml.trace.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * How the viewer writes the timestamps it adds to live trace messages. Timestamps that were already
 * in the text (e.g. in a trace file) are never rewritten. Times are shown in the local time zone.
 */
public enum TimestampFormat {
    US("U.S.", "MM/dd/yy HH:mm:ss.SSS"),
    EUROPEAN("European", "dd.MM.yy HH:mm:ss.SSS"),
    EUROPEAN_SLASH("European (slashes)", "dd/MM/yy HH:mm:ss.SSS"),
    ISO("ISO 8601", "yyyy-MM-dd HH:mm:ss.SSS"),
    TIME_ONLY("Time only", "HH:mm:ss.SSS");

    /** The DSTrace console's own format, and the default. */
    public static final TimestampFormat DEFAULT = US;

    private final String label;
    private final String pattern;
    private final DateTimeFormatter formatter;

    TimestampFormat(String label, String pattern) {
        this.label = label;
        this.pattern = pattern;
        this.formatter = DateTimeFormatter.ofPattern(pattern);
    }

    public String label() {
        return label;
    }

    public String pattern() {
        return pattern;
    }

    public String format(long epochMillis) {
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** The constant with this name, or {@link #DEFAULT} if there is none (e.g. an old preference). */
    public static TimestampFormat fromName(String name) {
        for (TimestampFormat f : values()) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return DEFAULT;
    }
}
