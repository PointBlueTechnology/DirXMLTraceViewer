package com.pointbluetech.dirxml.trace.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Document;
import javax.swing.text.Highlighter;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Find-in-trace bar: highlights every match in the displayed trace and steps through them. Unlike
 * the "Contains" filter it hides nothing. Enter / Shift+Enter move to the next / previous match,
 * Escape closes the bar.
 */
final class FindBar extends JPanel {

    private static final Highlighter.HighlightPainter ALL = new DefaultHighlighter.DefaultHighlightPainter(new Color(0x5C4B1E));
    private static final Highlighter.HighlightPainter CURRENT = new DefaultHighlighter.DefaultHighlightPainter(new Color(0xB8860B));

    private final JTextPane pane;
    private final JTextField field = new JTextField(24);
    private final JCheckBox matchCase = new JCheckBox("Match case");
    private final JLabel count = new JLabel(" ");
    private final Runnable onOpen;
    private final List<int[]> matches = new ArrayList<>();
    private final List<Object> tags = new ArrayList<>();
    private Object currentTag;
    private int current = -1;
    private final Timer recount = new Timer(250, e -> search(false));

    /** @param onOpen called when the bar is shown (e.g. to pause the live view) */
    FindBar(JTextPane pane, Runnable onOpen) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 4));
        this.pane = pane;
        this.onOpen = onOpen;
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, javax.swing.UIManager.getColor("Separator.foreground")));
        recount.setRepeats(false);

        field.putClientProperty("JTextField.placeholderText", "Find in trace");
        field.putClientProperty("JTextField.showClearButton", true);
        JButton prev = new JButton("▲");
        JButton next = new JButton("▼");
        JButton close = new JButton("✕");
        prev.setToolTipText("Previous match (Shift+Enter)");
        next.setToolTipText("Next match (Enter)");
        close.setToolTipText("Close (Esc)");
        for (JButton b : List.of(prev, next, close)) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
        }
        prev.addActionListener(e -> step(-1));
        next.addActionListener(e -> step(1));
        close.addActionListener(e -> close());
        matchCase.setFocusable(false);
        matchCase.addActionListener(e -> search(true));

        add(new JLabel("Find:"));
        add(field);
        add(prev);
        add(next);
        add(matchCase);
        add(count);
        add(close);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { search(true); }
            @Override public void removeUpdate(DocumentEvent e) { search(true); }
            @Override public void changedUpdate(DocumentEvent e) { search(true); }
        });
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), () -> step(1));
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK), () -> step(-1));
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), this::close);

        // The trace pane swaps documents when it is rebuilt and grows while streaming; keep matches current.
        DocumentListener contentChanged = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { recount.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { recount.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        };
        pane.getDocument().addDocumentListener(contentChanged);
        pane.addPropertyChangeListener("document", e -> {
            if (e.getOldValue() instanceof Document d) d.removeDocumentListener(contentChanged);
            if (e.getNewValue() instanceof Document d) d.addDocumentListener(contentChanged);
            recount.restart();
        });
        setVisible(false);
    }

    void open() {
        onOpen.run();
        setVisible(true);
        String sel = pane.getSelectedText();
        if (sel != null && !sel.isBlank() && !sel.contains("\n")) {
            field.setText(sel);
        }
        field.selectAll();
        field.requestFocusInWindow();
        search(true);
    }

    void close() {
        setVisible(false);
        clearHighlights();
        matches.clear();
        current = -1;
        pane.requestFocusInWindow();
    }

    /** @param jump move to the first match at or after the caret (new query) rather than keep position */
    private void search(boolean jump) {
        if (!isVisible()) return;
        clearHighlights();
        matches.clear();
        String q = field.getText();
        if (q.isEmpty()) {
            current = -1;
            count.setText(" ");
            return;
        }
        String text;
        try {
            Document d = pane.getDocument();
            text = d.getText(0, d.getLength());
        } catch (BadLocationException e) {
            return;
        }
        String hay = matchCase.isSelected() ? text : text.toLowerCase(Locale.ROOT);
        String needle = matchCase.isSelected() ? q : q.toLowerCase(Locale.ROOT);
        Highlighter h = pane.getHighlighter();
        for (int i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length())) {
            matches.add(new int[]{i, i + needle.length()});
            try {
                tags.add(h.addHighlight(i, i + needle.length(), ALL));
            } catch (BadLocationException ignored) {
            }
        }
        if (matches.isEmpty()) {
            current = -1;
            count.setText("No matches");
            return;
        }
        if (jump || current < 0 || current >= matches.size()) {
            int caret = pane.getCaretPosition();
            current = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] >= caret) {
                    current = i;
                    break;
                }
            }
        }
        showCurrent(jump);
    }

    /** Moves to the next (1) or previous (-1) match, opening the bar if it is closed. */
    void step(int delta) {
        if (!isVisible()) {
            open();
            return;
        }
        if (matches.isEmpty()) {
            search(true);
            return;
        }
        current = Math.floorMod(current + delta, matches.size());
        showCurrent(true);
    }

    private void showCurrent(boolean scroll) {
        Highlighter h = pane.getHighlighter();
        if (currentTag != null) h.removeHighlight(currentTag);
        int[] m = matches.get(current);
        try {
            currentTag = h.addHighlight(m[0], m[1], CURRENT);
            if (scroll) {
                pane.setCaretPosition(m[0]);
                Rectangle r = pane.modelToView2D(m[0]).getBounds();
                r.height *= 3;
                SwingUtilities.invokeLater(() -> pane.scrollRectToVisible(r));
            }
        } catch (BadLocationException ignored) {
        }
        count.setText((current + 1) + " of " + matches.size());
    }

    private void clearHighlights() {
        Highlighter h = pane.getHighlighter();
        tags.forEach(h::removeHighlight);
        tags.clear();
        if (currentTag != null) {
            h.removeHighlight(currentTag);
            currentTag = null;
        }
    }

    private static void bind(JComponent c, KeyStroke key, Runnable r) {
        String name = key.toString();
        c.getInputMap().put(key, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }
}
