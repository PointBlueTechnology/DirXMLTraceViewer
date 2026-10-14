package com.pointbluetech.dirxml.trace.model;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes syntax-highlighting runs for a trace record. Layers, lowest priority first:
 * XML and JSON markup, rule names, status/error keywords, the engine's own DSTrace colors, then
 * the message header.
 */
public final class TraceHighlighter {

    public enum Token {
        TEXT, TIMESTAMP, DRIVER, THREAD_SUB, THREAD_PUB, THREAD_SVC, PUNCT,
        XML_BRACKET, XML_TAG, XML_ATTR, XML_VALUE, XML_COMMENT, XML_PI, XML_CDATA,
        STATUS_ERROR, STATUS_WARNING, STATUS_SUCCESS, STATUS_RETRY,
        RULE_NAME, JSON_KEY, JSON_STRING, JSON_NUMBER, JSON_LITERAL, JSON_PUNCT,
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

    /**
     * The rule a policy is evaluating or applying, as the engine traces it ("Evaluating selection
     * criteria for rule {0}." and "Applying rule {0}."): group 1 is the name, without the period.
     */
    private static final Pattern RULE = Pattern.compile(
            "(?:Evaluating selection criteria for rule|Applying rule) ([^\\r\\n]+?)\\.?[ \\t]*(?=\\r?\\n|$)");

    private static final int DS_BASE = Token.values().length;

    private TraceHighlighter() {
    }

    public static List<Run> highlight(TraceRecord record) {
        String text = record.text();
        int n = text.length();
        // One slot per character: a Token ordinal, or DS_BASE + console color.
        int[] style = new int[n];

        lexXml(text, style);
        lexJson(text, style);
        Matcher rule = RULE.matcher(text);
        while (rule.find()) {
            fillIf(style, rule.start(1), rule.end(1), Token.TEXT, Token.RULE_NAME);
        }
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

    /**
     * JSON in a message (a REST shim's request or response, compact or pretty-printed): each
     * {@code {} or {@code [} outside XML that opens a well-formed value is lexed into keys, strings,
     * numbers, literals and punctuation. Anything that is not well-formed is left as text.
     */
    static void lexJson(String s, int[] style) {
        int n = s.length();
        int[] marks = null;   // 0 = untouched, else Token ordinal + 1; reused, cleared after each try
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if ((c != '{' && c != '[') || style[i] != Token.TEXT.ordinal() || !jsonOpening(s, i)) {
                continue;
            }
            if (marks == null) {
                marks = new int[n];
            }
            JsonLexer lexer = new JsonLexer(s, marks);
            int end = lexer.value(i);
            if (end > i) {
                for (int k = i; k < end; k++) {
                    if (marks[k] != 0 && style[k] == Token.TEXT.ordinal()) {
                        style[k] = marks[k] - 1;
                    }
                }
            }
            java.util.Arrays.fill(marks, i, Math.min(n, Math.max(lexer.hi, end)), 0);
            if (end > i) {
                i = end - 1;
            }
        }
    }

    /** Whether the bracket at {@code i} is followed by what a JSON object or array starts with. */
    private static boolean jsonOpening(String s, int i) {
        int j = i + 1;
        while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
        if (j >= s.length()) {
            return false;
        }
        char d = s.charAt(j);
        return s.charAt(i) == '{' ? d == '"' || d == '}' : d == '{' || d == '[' || d == '"' || d == ']';
    }

    /** A strict recursive-descent JSON lexer that marks tokens and gives up (returns -1) on anything malformed. */
    private static final class JsonLexer {
        private final String s;
        private final int[] marks;
        private int depth;
        /** One past the furthest index marked, for clearing. */
        int hi;

        JsonLexer(String s, int[] marks) {
            this.s = s;
            this.marks = marks;
        }

        /** The end of the value starting at {@code i} (after whitespace), or -1. */
        int value(int i) {
            i = ws(i);
            if (i >= s.length() || ++depth > 64) {
                return -1;
            }
            try {
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> object(i);
                    case '[' -> array(i);
                    case '"' -> string(i, Token.JSON_STRING);
                    default -> c == '-' || Character.isDigit(c) ? number(i) : literal(i);
                };
            } finally {
                depth--;
            }
        }

        private int object(int i) {
            mark(i, i + 1, Token.JSON_PUNCT);
            int p = ws(i + 1);
            if (p < s.length() && s.charAt(p) == '}') {
                mark(p, p + 1, Token.JSON_PUNCT);
                return p + 1;
            }
            while (true) {
                p = ws(p);
                if (p >= s.length() || s.charAt(p) != '"') return -1;
                p = string(p, Token.JSON_KEY);
                if (p < 0) return -1;
                p = ws(p);
                if (p >= s.length() || s.charAt(p) != ':') return -1;
                mark(p, p + 1, Token.JSON_PUNCT);
                p = value(p + 1);
                if (p < 0) return -1;
                p = ws(p);
                if (p >= s.length()) return -1;
                mark(p, p + 1, Token.JSON_PUNCT);
                if (s.charAt(p) == '}') return p + 1;
                if (s.charAt(p) != ',') return -1;
                p++;
            }
        }

        private int array(int i) {
            mark(i, i + 1, Token.JSON_PUNCT);
            int p = ws(i + 1);
            if (p < s.length() && s.charAt(p) == ']') {
                mark(p, p + 1, Token.JSON_PUNCT);
                return p + 1;
            }
            while (true) {
                p = value(p);
                if (p < 0) return -1;
                p = ws(p);
                if (p >= s.length()) return -1;
                mark(p, p + 1, Token.JSON_PUNCT);
                if (s.charAt(p) == ']') return p + 1;
                if (s.charAt(p) != ',') return -1;
                p++;
            }
        }

        private int string(int i, Token t) {
            int p = i + 1;
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == '\\') {
                    p += 2;
                } else if (c == '"') {
                    mark(i, p + 1, t);
                    return p + 1;
                } else if (c == '\n') {
                    return -1;
                } else {
                    p++;
                }
            }
            return -1;
        }

        private int number(int i) {
            int p = i;
            if (s.charAt(p) == '-') p++;
            int digits = p;
            while (p < s.length() && (Character.isDigit(s.charAt(p)) || ".eE+-".indexOf(s.charAt(p)) >= 0)) p++;
            if (p == digits) return -1;
            mark(i, p, Token.JSON_NUMBER);
            return p;
        }

        private int literal(int i) {
            for (String w : new String[] {"true", "false", "null"}) {
                if (s.startsWith(w, i)) {
                    mark(i, i + w.length(), Token.JSON_LITERAL);
                    return i + w.length();
                }
            }
            return -1;
        }

        private int ws(int i) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            return i;
        }

        private void mark(int from, int to, Token t) {
            for (int k = from; k < to && k < marks.length; k++) {
                marks[k] = t.ordinal() + 1;
            }
            hi = Math.max(hi, Math.min(to, marks.length));
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
