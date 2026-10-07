package com.pointbluetech.dirxml.trace.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes syntax-highlighting runs for a trace record. Layers, lowest priority first:
 * XML markup, status/error keywords, the engine's own DSTrace colors, then the message header.
 */
public final class TraceHighlighter {

    public enum Token {
        TEXT, TIMESTAMP, DRIVER, THREAD_SUB, THREAD_PUB, THREAD_SVC, PUNCT,
        XML_BRACKET, XML_TAG, XML_ATTR, XML_VALUE, XML_COMMENT, XML_PI, XML_CDATA,
        STATUS_ERROR, STATUS_WARNING, STATUS_SUCCESS, STATUS_RETRY,
        DS_COLOR
    }

    /** A highlighted run; {@code dsColor} is only meaningful for {@link Token#DS_COLOR}. */
    public record Run(int start, int end, Token token, int dsColor) {
    }

    private static final Pattern STATUS = Pattern.compile(
            "<status\\b[^>]*?\\blevel=\"(error|fatal|warning|success|retry)\"[^>]*?(/>|>(.*?)</status>)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ERROR_WORDS = Pattern.compile(
            "Code\\(-\\d+\\)[^\\r\\n]*|\\b[\\w.$]+(?:Exception|Error)\\b[^\\r\\n]*|\\bDirXML Log Event\\b");

    private static final int DS_BASE = Token.values().length;

    private TraceHighlighter() {
    }

    public static List<Run> highlight(TraceRecord record) {
        String text = record.text();
        int n = text.length();
        // One slot per character: a Token ordinal, or DS_BASE + console color.
        int[] style = new int[n];

        lexXml(text, style);
        markStatus(text, style);
        Matcher m = ERROR_WORDS.matcher(text);
        while (m.find()) {
            fillIf(style, m.start(), m.end(), Token.TEXT, Token.STATUS_ERROR);
        }
        for (FormattedText.ColorSpan span : record.colorSpans()) {
            fill(style, span.start(), Math.min(span.end(), n), DS_BASE + span.color());
        }
        TraceRecord.Header h = record.header();
        if (h != null) {
            fill(style, 0, h.end(), Token.PUNCT.ordinal());
            fill(style, h.timestampStart(), h.timestampEnd(), Token.TIMESTAMP.ordinal());
            fill(style, h.nameStart(), h.nameEnd(), Token.DRIVER.ordinal());
            if (h.threadStart() >= 0) {
                Token t = switch (record.channel()) {
                    case PUBLISHER -> Token.THREAD_PUB;
                    case SERVICE -> Token.THREAD_SVC;
                    default -> Token.THREAD_SUB;
                };
                fill(style, h.threadStart(), h.threadEnd(), t.ordinal());
            }
        }
        return toRuns(style);
    }

    private static List<Run> toRuns(int[] style) {
        List<Run> runs = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= style.length; i++) {
            if (i == style.length || style[i] != style[start]) {
                int s = style[start];
                runs.add(s >= DS_BASE
                        ? new Run(start, i, Token.DS_COLOR, s - DS_BASE)
                        : new Run(start, i, Token.values()[s], -1));
                start = i;
            }
        }
        return runs;
    }

    private static void markStatus(String text, int[] style) {
        Matcher m = STATUS.matcher(text);
        while (m.find()) {
            Token t = switch (m.group(1).toLowerCase()) {
                case "error", "fatal" -> Token.STATUS_ERROR;
                case "warning" -> Token.STATUS_WARNING;
                case "retry" -> Token.STATUS_RETRY;
                default -> Token.STATUS_SUCCESS;
            };
            fill(style, m.start(1), m.end(1), t.ordinal());
            if (m.group(3) != null) {
                fillIf(style, m.start(3), m.end(3), Token.TEXT, t);
            }
        }
    }

    /** A tolerant XML tokenizer: good enough for the pretty-printed documents in trace output. */
    static void lexXml(String s, int[] style) {
        int n = s.length();
        int i = s.indexOf('<');
        while (i >= 0 && i < n) {
            if (s.startsWith("<!--", i)) {
                i = region(s, style, i, "-->", Token.XML_COMMENT);
            } else if (s.startsWith("<![CDATA[", i)) {
                i = region(s, style, i, "]]>", Token.XML_CDATA);
            } else if (s.startsWith("<?", i) || s.startsWith("<!", i)) {
                i = region(s, style, i, s.charAt(i + 1) == '?' ? "?>" : ">", Token.XML_PI);
            } else if (i + 1 < n && isNameStart(s.charAt(i + 1) == '/' && i + 2 < n ? s.charAt(i + 2) : s.charAt(i + 1))) {
                i = tag(s, style, i);
            } else {
                i++;
            }
            i = i < n ? s.indexOf('<', i) : -1;
        }
    }

    private static int region(String s, int[] style, int start, String terminator, Token token) {
        int end = s.indexOf(terminator, start + 2);
        end = end < 0 ? s.length() : end + terminator.length();
        fill(style, start, end, token.ordinal());
        return end;
    }

    private static int tag(String s, int[] style, int i) {
        int n = s.length();
        int p = s.charAt(i + 1) == '/' ? i + 2 : i + 1;
        fill(style, i, p, Token.XML_BRACKET.ordinal());
        int nameEnd = p;
        while (nameEnd < n && isNameChar(s.charAt(nameEnd))) nameEnd++;
        fill(style, p, nameEnd, Token.XML_TAG.ordinal());
        p = nameEnd;
        while (p < n) {
            char c = s.charAt(p);
            if (c == '>') {
                fill(style, p, p + 1, Token.XML_BRACKET.ordinal());
                return p + 1;
            }
            if (c == '/' && p + 1 < n && s.charAt(p + 1) == '>') {
                fill(style, p, p + 2, Token.XML_BRACKET.ordinal());
                return p + 2;
            }
            if (c == '<') {
                return p; // malformed; restart lexing at the new tag
            }
            if (c == '"' || c == '\'') {
                int close = s.indexOf(c, p + 1);
                close = close < 0 ? n : close + 1;
                fill(style, p, close, Token.XML_VALUE.ordinal());
                p = close;
            } else if (isNameStart(c)) {
                int e = p;
                while (e < n && isNameChar(s.charAt(e))) e++;
                fill(style, p, e, Token.XML_ATTR.ordinal());
                p = e;
            } else {
                p++;
            }
        }
        return n;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '-' || c == '.';
    }

    private static void fill(int[] style, int from, int to, int value) {
        for (int k = Math.max(0, from); k < Math.min(to, style.length); k++) {
            style[k] = value;
        }
    }

    private static void fillIf(int[] style, int from, int to, Token only, Token value) {
        for (int k = Math.max(0, from); k < Math.min(to, style.length); k++) {
            if (style[k] == only.ordinal()) {
                style[k] = value.ordinal();
            }
        }
    }
}
