package com.pointbluetech.dirxml.trace.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/**
 * Renders a DSTrace format string and its typed parameters into plain text.
 * <p>
 * Two passes: printf-style conversions ({@code %s}, {@code %d}, {@code %x}, ...) are replaced by the
 * event parameters in order, then the DSTrace console color directives are removed and turned into
 * {@link FormattedText.ColorSpan}s:
 * <ul>
 *     <li>{@code %+C} - push the current color</li>
 *     <li>{@code %nC} - switch to console color {@code n} (0-15)</li>
 *     <li>{@code %-C} - pop back to the previous color</li>
 * </ul>
 * The DirXML engine uses these to highlight policy names, e.g.
 * {@code Applying policy: %+C%14Csub-ctp-DefaultValues%-C.}
 */
public final class DsTraceFormatter {

    private static final int NO_COLOR = -1;

    private DsTraceFormatter() {
    }

    public static FormattedText format(String format, List<Object> params) {
        return applyColors(substitute(format, params));
    }

    /** Strips color directives only; for text that is already formatted. */
    public static FormattedText applyColors(String text) {
        StringBuilder out = new StringBuilder(text.length());
        List<FormattedText.ColorSpan> spans = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        int current = NO_COLOR;
        int runStart = 0;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int directiveEnd = c == '%' ? colorDirectiveEnd(text, i) : -1;
            if (directiveEnd < 0) {
                out.append(c);
                i++;
                continue;
            }
            int newColor;
            char op = text.charAt(i + 1);
            if (op == '+') {
                stack.push(current);
                newColor = current;
            } else if (op == '-') {
                newColor = stack.isEmpty() ? NO_COLOR : stack.pop();
            } else {
                newColor = Integer.parseInt(text, i + 1, directiveEnd - 1, 10) & 0x0F;
            }
            if (newColor != current) {
                if (current != NO_COLOR && out.length() > runStart) {
                    spans.add(new FormattedText.ColorSpan(runStart, out.length(), current));
                }
                current = newColor;
                runStart = out.length();
            }
            i = directiveEnd;
        }
        if (current != NO_COLOR && out.length() > runStart) {
            spans.add(new FormattedText.ColorSpan(runStart, out.length(), current));
        }
        return new FormattedText(out.toString(), spans);
    }

    /** Replaces printf-style conversions with parameters, leaving color directives in place. */
    static String substitute(String format, List<Object> params) {
        StringBuilder out = new StringBuilder(format.length() + 64);
        int next = 0;
        int n = format.length();
        int i = 0;
        while (i < n) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 >= n) {
                out.append(c);
                i++;
                continue;
            }
            if (format.charAt(i + 1) == '%') {
                out.append('%');
                i += 2;
                continue;
            }
            int end = colorDirectiveEnd(format, i);
            if (end > 0) {
                out.append(format, i, end);
                i = end;
                continue;
            }
            Spec spec = Spec.parse(format, i);
            if (spec == null || next >= params.size()) {
                // Not a conversion we understand, or no parameter left for it: keep it literally.
                out.append(c);
                i++;
                continue;
            }
            out.append(spec.render(params.get(next++)));
            i = spec.end;
        }
        return out.toString();
    }

    private static int colorDirectiveEnd(String s, int i) {
        int n = s.length();
        if (i + 2 < n && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-') && s.charAt(i + 2) == 'C') {
            return i + 3;
        }
        int j = i + 1;
        while (j < n && j - i <= 2 && Character.isDigit(s.charAt(j))) {
            j++;
        }
        return j > i + 1 && j < n && s.charAt(j) == 'C' ? j + 1 : -1;
    }

    /** A parsed printf conversion: {@code %[flags][width][.precision][length]conversion}. */
    private record Spec(boolean leftAlign, boolean zeroPad, int width, int precision, char conversion, int end) {

        static Spec parse(String s, int start) {
            int n = s.length();
            int i = start + 1;
            boolean left = false;
            boolean zero = false;
            while (i < n && "-+ #0".indexOf(s.charAt(i)) >= 0) {
                if (s.charAt(i) == '-') left = true;
                if (s.charAt(i) == '0') zero = true;
                i++;
            }
            int width = 0;
            while (i < n && Character.isDigit(s.charAt(i))) {
                width = width * 10 + (s.charAt(i++) - '0');
            }
            int precision = -1;
            if (i < n && s.charAt(i) == '.') {
                i++;
                precision = 0;
                while (i < n && Character.isDigit(s.charAt(i))) {
                    precision = precision * 10 + (s.charAt(i++) - '0');
                }
            }
            while (i < n && "hlLqjzt".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (i >= n || "sSdiuxXcpoeEfgGTU".indexOf(s.charAt(i)) < 0) {
                return null;
            }
            return new Spec(left, zero, width, precision, s.charAt(i), i + 1);
        }

        String render(Object value) {
            String text = switch (value) {
                case null -> "";
                case Number num when conversion == 'x' -> Long.toHexString(num.longValue() & 0xFFFFFFFFL);
                case Number num when conversion == 'X' -> Long.toHexString(num.longValue() & 0xFFFFFFFFL).toUpperCase();
                case Number num when conversion == 'u' -> Long.toString(num.longValue() & 0xFFFFFFFFL);
                case Number num when conversion == 'c' -> String.valueOf((char) num.intValue());
                case byte[] bytes -> HexFormat.of().formatHex(bytes);
                default -> String.valueOf(value);
            };
            if (precision >= 0 && (conversion == 's' || conversion == 'S') && text.length() > precision) {
                text = text.substring(0, precision);
            }
            if (text.length() >= width) {
                return text;
            }
            String pad = String.valueOf(zeroPad && !leftAlign ? '0' : ' ').repeat(width - text.length());
            return leftAlign ? text + pad : pad + text;
        }
    }
}
