package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.TraceHighlighter.Token;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.Font;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

/**
 * The colored trace pane. Keeps about {@code maxRecords} records in the document.
 * <p>
 * Performance notes: records arrive pre-rendered ({@link TraceEntry}), so appending only inserts
 * text; a full rebuild is done off the event thread with {@link #build} and swapped in with
 * {@link #install}; and trimming happens in chunks so the front of the document is not re-laid out
 * on every batch.
 */
final class TraceView extends JScrollPane {

    /** A document built off the event thread, ready to {@link #install}. */
    record Built(DefaultStyledDocument doc, Deque<Integer> lengths) {
    }

    private final WrappingTextPane pane = new WrappingTextPane();
    /** Style per {@link TraceEntry.Rendered} style code. Immutable once built, so safe to share across threads. */
    private final AttributeSet[] styles = new AttributeSet[TraceEntry.TOKEN_COUNT + 16];
    /** Length of each displayed record, oldest first, for trimming. */
    private Deque<Integer> lengths = new ArrayDeque<>();
    private DefaultStyledDocument doc = new DefaultStyledDocument();
    private volatile int maxRecords;
    private boolean autoScroll = true;
    private boolean scrollPending;
    private volatile boolean tagServer;
    private volatile boolean compact;

    TraceView(int maxRecords) {
        this.maxRecords = maxRecords;
        pane.setEditable(false);
        pane.setBackground(TracePalette.BACKGROUND);
        pane.setForeground(TracePalette.FOREGROUND);
        pane.setCaretColor(TracePalette.FOREGROUND);
        pane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ((DefaultCaret) pane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        pane.setStyledDocument(doc);
        setViewportView(pane);
        getViewport().setBackground(TracePalette.BACKGROUND);
        getVerticalScrollBar().setUnitIncrement(16);

        for (Token t : Token.values()) {
            if (t != Token.DS_COLOR) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, TracePalette.TOKENS.get(t));
                StyleConstants.setBold(a, TracePalette.BOLD.getOrDefault(t, false));
                styles[t.ordinal()] = a;
            }
        }
        for (int i = 0; i < 16; i++) {
            SimpleAttributeSet a = new SimpleAttributeSet();
            StyleConstants.setForeground(a, TracePalette.DS_COLORS[i]);
            styles[TraceEntry.TOKEN_COUNT + i] = a;
        }
    }

    JTextPane textPane() {
        return pane;
    }

    void setWrap(boolean wrap) {
        pane.wrap = wrap;
        pane.revalidate();
    }

    void setAutoScroll(boolean autoScroll) {
        this.autoScroll = autoScroll;
        if (autoScroll) {
            scrollToEnd();
        }
    }

    /** Prefix message headers with the server name; applies to records rendered afterwards. */
    void setTagServer(boolean tagServer) {
        this.tagServer = tagServer;
    }

    boolean tagServer() {
        return tagServer;
    }

    /** Drop whitespace-only lines from records rendered afterwards (the stored trace is unchanged). */
    void setCompact(boolean compact) {
        this.compact = compact;
    }

    boolean compact() {
        return compact;
    }

    int maxRecords() {
        return maxRecords;
    }

    void setMaxRecords(int maxRecords) {
        this.maxRecords = maxRecords;
        trim(maxRecords);
    }

    void setFontSize(float size) {
        pane.setFont(pane.getFont().deriveFont(size));
    }

    float fontSize() {
        return pane.getFont().getSize2D();
    }

    int displayedRecords() {
        return lengths.size();
    }

    /** Appends records as a single document edit. Call on the event thread. */
    void append(Collection<TraceEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        boolean c = compact, t = tagServer;
        for (TraceEntry e : entries) {
            lengths.addLast(add(doc, e.render(c, t)));
        }
        // Let the document overshoot a little so trimming (a relayout of the whole view) is occasional.
        if (lengths.size() > maxRecords + Math.max(200, maxRecords / 10)) {
            trim(maxRecords);
        }
        if (autoScroll) {
            scrollToEnd();
        }
    }

    /** Builds a complete document for these records. Safe to call on any thread. */
    Built build(Collection<TraceEntry> entries) {
        boolean c = compact, t = tagServer;
        DefaultStyledDocument d = new DefaultStyledDocument();
        Deque<Integer> lens = new ArrayDeque<>();
        int skip = Math.max(0, entries.size() - maxRecords);
        for (TraceEntry e : entries) {
            if (skip-- > 0) continue;
            lens.addLast(add(d, e.render(c, t)));
        }
        return new Built(d, lens);
    }

    /** Shows a document from {@link #build}. Call on the event thread. */
    void install(Built built) {
        doc = built.doc();
        lengths = built.lengths();
        pane.setStyledDocument(doc);
        if (autoScroll) {
            scrollToEnd();
        }
    }

    void clear() {
        install(new Built(new DefaultStyledDocument(), new ArrayDeque<>()));
    }

    private int add(DefaultStyledDocument d, TraceEntry.Rendered r) {
        String text = r.text();
        try {
            for (int i = 0; i < r.starts().length; i++) {
                d.insertString(d.getLength(), text.substring(r.starts()[i], r.ends()[i]), styles[r.styles()[i]]);
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        return text.length();
    }

    private void trim(int keep) {
        int remove = 0;
        while (lengths.size() > keep) {
            remove += lengths.removeFirst();
        }
        if (remove > 0) {
            try {
                doc.remove(0, Math.min(remove, doc.getLength()));
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Jumps to the bottom once the pending layout is done. Moving the caret and calling
     * scrollRectToVisible instead forces a full layout on every append, which was the single biggest
     * cost of streaming. Calls made before the jump runs are coalesced.
     */
    private void scrollToEnd() {
        if (scrollPending) {
            return;
        }
        scrollPending = true;
        SwingUtilities.invokeLater(() -> {
            scrollPending = false;
            if (autoScroll) {
                JScrollBar bar = getVerticalScrollBar();
                bar.setValue(bar.getMaximum() - bar.getVisibleAmount());
            }
        });
    }

    /** JTextPane that can turn line wrapping off (it always wraps by default). */
    private static final class WrappingTextPane extends JTextPane {
        boolean wrap;

        @Override
        public boolean getScrollableTracksViewportWidth() {
            // Not wrapping: size to the content. (Asking the UI for the preferred width here, as is
            // commonly done, measures the whole document on every layout pass.)
            return wrap;
        }
    }
}
