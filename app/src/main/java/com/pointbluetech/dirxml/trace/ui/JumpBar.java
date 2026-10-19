package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.TraceMarker;
import com.pointbluetech.dirxml.trace.model.TraceTime;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;

/**
 * Jump markers (issue #2): pick a kind of place in the trace — a driver start, an event on either
 * channel, a policy set or policy, the end of a transaction, a log event — and step to the next or
 * previous one, or the most recent; or go to a time of day. Jumping turns auto-scroll off, so the
 * view stays where it landed while trace keeps arriving.
 */
final class JumpBar extends JPanel {

    /** The single key that jumps to the next of a kind in the trace (with Shift, the previous). */
    static final Map<TraceMarker, Character> KEYS = new EnumMap<>(Map.of(
            TraceMarker.PUBLISHER_EVENT, 'P',
            TraceMarker.SUBSCRIBER_EVENT, 'S',
            TraceMarker.SUBSCRIBER_EXECUTE, 'X',
            TraceMarker.POLICY_SET, 'M',
            TraceMarker.POLICY, 'N',
            TraceMarker.POLICY_RESULT, 'R',
            TraceMarker.END_TRANSACTION, 'T',
            TraceMarker.DIRXML_LOG_EVENT, 'L',
            TraceMarker.PUMPING_XDS, 'E'));

    private final TraceView view;
    private final Runnable stopAutoScroll;
    private final JComboBox<TraceMarker> kind = new JComboBox<>(TraceMarker.values());
    private final JTextField time = new JTextField(8);
    private final JLabel note = new JLabel();
    private final Timer clearNote = new Timer(3000, e -> note.setText(""));

    JumpBar(TraceView view, Runnable stopAutoScroll) {
        super(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        this.view = view;
        this.stopAutoScroll = stopAutoScroll;
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));
        clearNote.setRepeats(false);
        kind.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                if (value instanceof TraceMarker m) {
                    Character key = KEYS.get(m);
                    value = key == null ? m.label() : m.label() + "  (" + Character.toLowerCase(key) + ")";
                }
                return super.getListCellRendererComponent(list, value, index, selected, focus);
            }
        });
        kind.setSelectedItem(TraceMarker.SUBSCRIBER_EVENT);
        kind.setToolTipText("The kind of place to jump to");
        JButton prev = new JButton("◀");
        prev.setToolTipText("Previous " + "(" + shortcut("UP") + ")");
        prev.addActionListener(e -> jump(-1));
        JButton next = new JButton("▶");
        next.setToolTipText("Next (" + shortcut("DOWN") + ")");
        next.addActionListener(e -> jump(1));
        JButton last = new JButton("Most recent");
        last.setToolTipText("The most recent one (" + shortcut("⇧DOWN") + ")");
        last.addActionListener(e -> jump(0));
        time.setToolTipText("A time of day, e.g. 10:15 or 10:15:12.345; Enter goes to the first message at or after it");
        time.putClientProperty("JTextField.placeholderText", "hh:mm:ss");
        time.addActionListener(e -> goToTime());
        note.setForeground(TracePalette.TOKENS.get(com.pointbluetech.dirxml.trace.model.TraceHighlighter.Token.STATUS_WARNING));
        add(note);
        add(new JLabel("Jump to"));
        add(kind);
        add(prev);
        add(next);
        add(last);
        add(new JLabel("  Time"));
        add(time);
    }

    private static String shortcut(String key) {
        return (System.getProperty("os.name").startsWith("Mac") ? "⌘" : "Ctrl+") + key;
    }

    /** 1 next, -1 previous, 0 most recent, of the chosen kind. */
    void jump(int direction) {
        jump((TraceMarker) kind.getSelectedItem(), direction);
    }

    /** 1 next, -1 previous, 0 most recent, of {@code k}. */
    void jump(TraceMarker k, int direction) {
        stopAutoScroll.run();
        if (!view.jump(k, direction)) {
            say("No " + (direction > 0 ? "later " : direction < 0 ? "earlier " : "") + k.label().toLowerCase() + " in view");
        }
    }

    void focusTime() {
        time.requestFocusInWindow();
        time.selectAll();
    }

    private void goToTime() {
        long t = TraceTime.parse(time.getText().trim());
        if (t < 0) {
            say("Not a time of day: try 10:15 or 10:15:12.345");
            return;
        }
        stopAutoScroll.run();
        if (!view.goToTime(t)) {
            say("Nothing in view at or after " + time.getText().trim());
        }
    }

    private void say(String s) {
        note.setText(s);
        clearNote.restart();
    }
}
