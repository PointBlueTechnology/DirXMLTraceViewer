package com.pointbluetech.dirxml.trace.ui;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultCaret;
import javax.swing.text.JTextComponent;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

/**
 * Copy for the trace text panes. Right-click (control-click on macOS) opens a one-item menu;
 * Copy is enabled only when text is selected. Ctrl/Cmd+C is left on the text component's own
 * keymap, so the shortcut keeps working when the menu is closed.
 * <p>
 * {@link JTextComponent#setComponentPopupMenu} is what actually shows the menu: the look and feel
 * listens for {@link MouseEvent#isPopupTrigger()} on both press and release, which is the
 * difference between macOS and Windows/Linux. A click inside the current selection does not move
 * the caret. On macOS the popup consumes mouse-pressed, and the caret would otherwise treat the
 * matching mouse-released as a normal click and drop the selection before Copy is chosen.
 */
final class TraceCopy {

    private TraceCopy() {
    }

    /** Selected text to put on the clipboard, or null when there is nothing to copy. */
    static String textToCopy(String selectedText) {
        if (selectedText == null || selectedText.isEmpty()) {
            return null;
        }
        return selectedText;
    }

    /**
     * True when {@code offset} lies inside the selection described by {@code dot} and {@code mark}.
     * The range is half-open, matching {@link JTextComponent#getSelectionStart()} /
     * {@link JTextComponent#getSelectionEnd()}. A negative offset (the click did not map to a
     * character) does not count.
     */
    static boolean clickKeepsSelection(int dot, int mark, int offset) {
        if (offset < 0) {
            return false;
        }
        int start = Math.min(dot, mark);
        int end = Math.max(dot, mark);
        return start < end && offset >= start && offset < end;
    }

    /** Popup menu plus the caret that keeps a selection when the popup click is inside it. */
    static void install(JTextComponent component) {
        component.setCaret(new PopupSelectionCaret());
        component.setComponentPopupMenu(new CopyMenu(component));
    }

    private static void copyToClipboard(String text) {
        StringSelection contents = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(contents, contents);
    }

    private static KeyStroke copyAccelerator() {
        int mask = GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        return KeyStroke.getKeyStroke(KeyEvent.VK_C, mask);
    }

    private static boolean popupGesture(MouseEvent e) {
        return e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e);
    }

    /** Remembers, when the menu opens, the selection Copy should write to the clipboard. */
    private static final class CopyMenu extends JPopupMenu {
        private final JTextComponent component;
        private final JMenuItem copy = new JMenuItem("Copy");
        private String pending;

        CopyMenu(JTextComponent component) {
            this.component = component;
            copy.setAccelerator(copyAccelerator());
            copy.addActionListener(e -> {
                if (pending != null) {
                    copyToClipboard(pending);
                }
            });
            add(copy);
            addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    pending = textToCopy(component.getSelectedText());
                    copy.setEnabled(pending != null);
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
        }
    }

    /**
     * Default caret, except a popup click inside the selection leaves the dot and mark alone,
     * including the mouse-released that follows a consumed mouse-pressed.
     */
    private static final class PopupSelectionCaret extends DefaultCaret {
        /** The press landed inside the selection; ignore the rest of this click. */
        private boolean keep;

        @Override
        public void mousePressed(MouseEvent e) {
            keep = keepsSelection(e);
            if (keep) {
                JTextComponent c = getComponent();
                if (c != null && c.isShowing() && c.isRequestFocusEnabled() && !c.hasFocus()) {
                    c.requestFocusInWindow();
                }
                return;
            }
            super.mousePressed(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (keep) {
                return;
            }
            super.mouseReleased(e);
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (keep) {
                keep = false;
                return;
            }
            super.mouseClicked(e);
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (keep) {
                return;
            }
            super.mouseDragged(e);
        }

        private boolean keepsSelection(MouseEvent e) {
            if (!popupGesture(e)) {
                return false;
            }
            JTextComponent c = getComponent();
            if (c == null) {
                return false;
            }
            return clickKeepsSelection(c.getSelectionStart(), c.getSelectionEnd(), c.viewToModel2D(e.getPoint()));
        }
    }
}
