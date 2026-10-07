package com.pointbluetech.dirxml.trace.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCopyTest {

    @Test
    void textToCopyKeepsWhateverIsSelected() {
        assertNull(TraceCopy.textToCopy(null));
        assertNull(TraceCopy.textToCopy(""));
        assertEquals(" ", TraceCopy.textToCopy(" "));
        assertEquals("\n\n", TraceCopy.textToCopy("\n\n"));
        assertEquals("PT:\n<status>success</status>", TraceCopy.textToCopy("PT:\n<status>success</status>"));
    }

    @Test
    void clickInsideSelectionKeepsIt() {
        assertTrue(TraceCopy.clickKeepsSelection(4, 16, 4));
        assertTrue(TraceCopy.clickKeepsSelection(4, 16, 15));
        assertTrue(TraceCopy.clickKeepsSelection(16, 4, 10));
        assertFalse(TraceCopy.clickKeepsSelection(4, 16, 16));
        assertFalse(TraceCopy.clickKeepsSelection(4, 16, 3));
        assertFalse(TraceCopy.clickKeepsSelection(8, 8, 8));
        assertFalse(TraceCopy.clickKeepsSelection(0, 5, -1));
    }

    @Test
    void copyItemIsEnabledOnlyWhenTextIsSelected() throws Exception {
        onEdt(() -> {
            JTextPane pane = new JTextPane();
            pane.setText("hello trace");
            TraceCopy.install(pane);

            JPopupMenu menu = pane.getComponentPopupMenu();
            assertNotNull(menu);
            JMenuItem copy = (JMenuItem) menu.getComponent(0);
            assertEquals("Copy", copy.getText());
            assertNotNull(copy.getAccelerator());

            becomeVisible(menu);
            assertFalse(copy.isEnabled());

            pane.select(0, 5);
            becomeVisible(menu);
            assertTrue(copy.isEnabled());

            pane.setCaretPosition(3);
            becomeVisible(menu);
            assertFalse(copy.isEnabled());
        });
    }

    @Test
    void keyboardCopyBindingStaysOnTheTextComponent() throws Exception {
        onEdt(() -> {
            JTextPane pane = new JTextPane();
            TraceCopy.install(pane);
            KeyStroke ctrlC = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK);
            KeyStroke metaC = KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.META_DOWN_MASK);
            Object binding = pane.getInputMap().get(ctrlC);
            if (binding == null) {
                binding = pane.getInputMap().get(metaC);
            }
            assertEquals(DefaultEditorKit.copyAction, binding);
            assertNotNull(pane.getActionMap().get(DefaultEditorKit.copyAction));
        });
    }

    @Test
    void traceViewUsesTheCopyMenu() throws Exception {
        onEdt(() -> {
            TraceView view = new TraceView(10);
            JPopupMenu menu = view.textPane().getComponentPopupMenu();
            assertNotNull(menu);
            assertEquals("Copy", ((JMenuItem) menu.getComponent(0)).getText());
        });
    }

    @Test
    void popupClickInsideSelectionDoesNotMoveTheCaret() throws Exception {
        onEdt(() -> {
            JTextPane pane = paneWithText();
            // No popup menu: showing one requires a realized window, and the caret is what this checks.
            TraceCopy.install(pane);
            pane.setComponentPopupMenu(null);
            pane.select(4, 16);

            click(pane, 10, MouseEvent.BUTTON3, 0, true);
            assertEquals("efghijklmnop", pane.getSelectedText());

            // macOS: control-click is the left button and the popup trigger.
            click(pane, 10, MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK, true);
            assertEquals("efghijklmnop", pane.getSelectedText());

            // DefaultCaret would extend the selection on shift+control-click even when it is a popup.
            click(pane, 8, MouseEvent.BUTTON1, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, true);
            assertEquals("efghijklmnop", pane.getSelectedText());
        });
    }

    @Test
    void shiftClickOutsideTheSelectionStillExtendsIt() throws Exception {
        onEdt(() -> {
            JTextPane pane = paneWithText();
            TraceCopy.install(pane);
            pane.setComponentPopupMenu(null);
            pane.select(4, 16);

            click(pane, 22, MouseEvent.BUTTON1, InputEvent.SHIFT_DOWN_MASK, false);
            String selected = pane.getSelectedText();
            assertNotNull(selected);
            assertTrue(selected.startsWith("efghijklmnop"), selected);
            assertTrue(selected.length() > "efghijklmnop".length(), selected);
        });
    }

    /** Swing text layout is not safe off the event thread; headless tests still run there. */
    private static void onEdt(Runnable action) throws Exception {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    private static JTextPane paneWithText() {
        JTextPane pane = new JTextPane();
        pane.setText("abcdefghijklmnopqrstuvwxyz");
        pane.setSize(600, 80);
        return pane;
    }

    private static void becomeVisible(JPopupMenu menu) {
        PopupMenuEvent event = new PopupMenuEvent(menu);
        for (PopupMenuListener listener : menu.getPopupMenuListeners()) {
            listener.popupMenuWillBecomeVisible(event);
        }
    }

    private static void click(JTextPane pane, int offset, int button, int modifiers, boolean popupOnPress) {
        Rectangle2D r;
        try {
            r = pane.modelToView2D(offset);
        } catch (BadLocationException e) {
            throw new AssertionError(e);
        }
        assertNotNull(r);
        int x = (int) r.getX() + 1;
        int y = (int) r.getY() + (int) r.getHeight() / 2;
        long when = System.currentTimeMillis();
        pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_PRESSED, when, modifiers, x, y, 1, popupOnPress, button));
        pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_RELEASED, when, modifiers, x, y, 1, false, button));
        pane.dispatchEvent(new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, when, modifiers, x, y, 1, false, button));
    }
}
