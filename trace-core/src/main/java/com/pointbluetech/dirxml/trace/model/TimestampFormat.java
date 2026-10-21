package com.pointbluetech.dirxml.trace.model;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How the viewer displays timestamps. Date/time formats apply to viewer-added live timestamps;
 * {@link #DELTA} also calculates intervals for timestamps already in trace text. Times are shown in
 * the local time zone.
 */
public enum TimestampFormat {
    US("U.S.", "MM/dd/yy HH:mm:ss.SSS"),
    EUROPEAN("European", "dd.MM.yy HH:mm:ss.SSS"),
    EUROPEAN_SLASH("European (slashes)", "dd/MM/yy HH:mm:ss.SSS"),
    ISO("ISO 8601", "yyyy-MM-dd HH:mm:ss.SSS"),
    TIME_ONLY("Time only", "HH:mm:ss.SSS"),
    DELTA("Delta (seconds)", null);

    /** The DSTrace console's own format, and the default. */
    public static final TimestampFormat DEFAULT = US;

    private final String label;
    private final String pattern;
    private final DateTimeFormatter formatter;

    TimestampFormat(String label, String pattern) {
        this.label = label;
        this.pattern = pattern;
        this.formatter = pattern == null ? null : DateTimeFormatter.ofPattern(pattern);
    }

    public String label() {
        return label;
    }

    public String pattern() {
        return pattern == null ? "seconds.milliseconds" : pattern;
    }

    public String format(long epochMillis) {
        if (this == DELTA) {
            return formatDelta(0);
        }
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** Formats a signed interval in seconds with millisecond precision. */
    public String formatDelta(long deltaMillis) {
        long seconds = Math.abs(deltaMillis / 1000);
        long milliseconds = Math.abs(deltaMillis % 1000);
        return String.format(Locale.ROOT, "%s%d.%03d", deltaMillis < 0 ? "-" : "", seconds, milliseconds);
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
