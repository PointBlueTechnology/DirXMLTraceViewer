package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.TraceHighlighter.Token;

import java.awt.Color;
import java.util.EnumMap;
import java.util.Map;

/** Colors for the trace pane, tuned for a dark background. */
final class TracePalette {

    static final Color BACKGROUND = new Color(0x1E1F22);
    static final Color FOREGROUND = new Color(0xD4D4D4);

    /** DSTrace console colors 0-15 (DOS text palette), brightened where needed for a dark background. */
    static final Color[] DS_COLORS = {
            new Color(0x808080), new Color(0x569CD6), new Color(0x6A9955), new Color(0x4EC9B0),
            new Color(0xF44747), new Color(0xC586C0), new Color(0xCE9178), new Color(0xD4D4D4),
            new Color(0x808080), new Color(0x6CB6FF), new Color(0x89D185), new Color(0x4FC1FF),
            new Color(0xFF6B6B), new Color(0xE586E0), new Color(0xFFD866), new Color(0xFFFFFF),
    };

    static final Map<Token, Color> TOKENS = new EnumMap<>(Token.class);
    static final Map<Token, Boolean> BOLD = new EnumMap<>(Token.class);

    static {
        TOKENS.put(Token.TEXT, FOREGROUND);
        TOKENS.put(Token.TIMESTAMP, new Color(0x7F848E));
        TOKENS.put(Token.DRIVER, new Color(0xC586C0));
        TOKENS.put(Token.THREAD_SUB, new Color(0x4EC9B0));
        TOKENS.put(Token.THREAD_PUB, new Color(0xE5A04B));
        TOKENS.put(Token.THREAD_SVC, new Color(0xC586C0));
        TOKENS.put(Token.PUNCT, new Color(0x5C6370));
        TOKENS.put(Token.XML_BRACKET, new Color(0x808080));
        TOKENS.put(Token.XML_TAG, new Color(0x569CD6));
        TOKENS.put(Token.XML_ATTR, new Color(0x9CDCFE));
        TOKENS.put(Token.XML_VALUE, new Color(0xCE9178));
        TOKENS.put(Token.XML_COMMENT, new Color(0x6A9955));
        TOKENS.put(Token.XML_PI, new Color(0x808080));
        TOKENS.put(Token.XML_CDATA, new Color(0xB5CEA8));
        TOKENS.put(Token.STATUS_ERROR, new Color(0xFF5F5F));
        TOKENS.put(Token.STATUS_WARNING, new Color(0xFFB454));
        TOKENS.put(Token.STATUS_SUCCESS, new Color(0x7BD88F));
        TOKENS.put(Token.STATUS_RETRY, new Color(0xE5C07B));
        BOLD.put(Token.DRIVER, true);
        BOLD.put(Token.THREAD_SUB, true);
        BOLD.put(Token.THREAD_PUB, true);
        BOLD.put(Token.THREAD_SVC, true);
        BOLD.put(Token.STATUS_ERROR, true);
        BOLD.put(Token.STATUS_WARNING, true);
        BOLD.put(Token.STATUS_SUCCESS, true);
    }

    private TracePalette() {
    }
}
