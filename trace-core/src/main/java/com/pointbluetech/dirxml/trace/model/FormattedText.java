package com.pointbluetech.dirxml.trace.model;

import java.util.List;

/**
 * Text produced from a DSTrace format string, with the color regions the engine requested
 * through {@code %+C}, {@code %nC} and {@code %-C} directives.
 */
public record FormattedText(String text, List<ColorSpan> colorSpans) {

    /** A region of {@code text} rendered in DSTrace console color {@code color} (0-15). */
    public record ColorSpan(int start, int end, int color) {
    }

    public FormattedText {
        colorSpans = List.copyOf(colorSpans);
    }
}
