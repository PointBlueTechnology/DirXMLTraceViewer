package com.pointbluetech.dirxml.trace.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.Caret;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.Graphics;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Test
    void theCaretIsWiderAndItsRepaintAreaCoversIt() throws Exception {
        JTextPane[] pane = new JTextPane[1];
        onEdt(() -> {
            pane[0] = paneWithText();
            pane[0].setEditable(false);
            pane[0].setCaretColor(Color.WHITE);
            TraceCopy.install(pane[0]);
            pane[0].setCaretPosition(10);
            pane[0].getCaret().setVisible(true);
        });
        // DefaultCaret works out its repaint area on a later turn of the event thread
        onEdt(() -> {
            try {
                BufferedImage image = new BufferedImage(600, 80, BufferedImage.TYPE_INT_RGB);
                Graphics graphics = image.getGraphics();
                try {
                    pane[0].getCaret().paint(graphics);
                } finally {
                    graphics.dispose();
                }
                Rectangle2D position = pane[0].modelToView2D(10);
                assertNotNull(position);
                int y = (int) position.getY() + 1;
                int first = -1;
                int width = 0;
                for (int x = 0; x < image.getWidth(); x++) {
                    if (image.getRGB(x, y) == Color.WHITE.getRGB()) {
                        first = first < 0 ? x : first;
                        width++;
                    }
                }
                assertEquals(TraceCopy.CARET_WIDTH, width, "painted caret width");
                Rectangle painted = new Rectangle(first, (int) position.getY(), width, (int) position.getHeight());
                assertTrue(((Rectangle) pane[0].getCaret()).contains(painted),
                        "Blink repaint bounds must cover the entire painted caret");
                assertEquals(TraceCopy.blinkRate(javax.swing.UIManager.get("TextPane.caretBlinkRate")), pane[0].getCaret().getBlinkRate());
            } catch (BadLocationException e) {
                throw new AssertionError(e);
            } finally {
                pane[0].getCaret().setVisible(false);
            }
        });
    }

    @Test
    void focusedReadOnlyCaretBlinksOffAndOn() throws Exception {
        CompletableFuture<Void> blinked = new CompletableFuture<>();
        Caret[] caret = new Caret[1];
        Timer[] observer = new Timer[1];
        onEdt(() -> {
            JTextPane pane = paneWithText();
            pane.setEditable(false);
            pane.setCaretColor(Color.WHITE);
            TraceCopy.install(pane);
            caret[0] = pane.getCaret();
            ((FocusListener) caret[0]).focusGained(new FocusEvent(pane, FocusEvent.FOCUS_GAINED));
            boolean[] sawOff = {false};
            observer[0] = new Timer(25, event -> {
                boolean visible = caret[0].isVisible();
                BufferedImage image = new BufferedImage(600, 80, BufferedImage.TYPE_INT_RGB);
                Graphics graphics = image.getGraphics();
                try {
                    caret[0].paint(graphics);
                } finally {
                    graphics.dispose();
                }
                try {
                    Rectangle2D position = pane.modelToView2D(caret[0].getDot());
                    assertNotNull(position);
                    int pixel = image.getRGB((int) position.getX(), (int) position.getY() + 1);
                    assertEquals(visible ? Color.WHITE.getRGB() : Color.BLACK.getRGB(), pixel);
                    if (!visible) {
                        sawOff[0] = true;
                    } else if (sawOff[0]) {
                        blinked.complete(null);
                    }
                } catch (BadLocationException | AssertionError failure) {
                    blinked.completeExceptionally(failure);
                }
            });
            observer[0].start();
        });
        try {
            blinked.get(10, TimeUnit.SECONDS);
        } finally {
            onEdt(() -> {
                observer[0].stop();
                caret[0].setVisible(false);
            });
        }
    }

    @Test
    void aCaretThatMovesWhileBlinkedOffShowsAtOnce() throws Exception {
        JTextPane[] pane = new JTextPane[1];
        onEdt(() -> {
            pane[0] = paneWithText();
            pane[0].setEditable(false);
            TraceCopy.install(pane[0]);
            ((FocusListener) pane[0].getCaret()).focusGained(new FocusEvent(pane[0], FocusEvent.FOCUS_GAINED));
        });
        try {
            boolean off = false;
            for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); !off && System.nanoTime() < end; ) {
                Thread.sleep(20);
                boolean[] visible = new boolean[1];
                onEdt(() -> visible[0] = pane[0].getCaret().isVisible());
                off = !visible[0];
            }
            assertTrue(off, "the caret should blink off");
            boolean[] visible = new boolean[1];
            onEdt(() -> {
                pane[0].setCaretPosition(20);
                visible[0] = pane[0].getCaret().isVisible();
            });
            assertTrue(visible[0], "a caret that just moved should show");
        } finally {
            onEdt(() -> pane[0].getCaret().setVisible(false));
        }
    }

    @Test
    void theBlinkRateComesFromTheLookAndFeel() {
        assertEquals(530, TraceCopy.blinkRate(530));
        assertEquals(0, TraceCopy.blinkRate(0), "a look and feel may turn blinking off");
        assertEquals(TraceCopy.DEFAULT_BLINK_RATE, TraceCopy.blinkRate(null));
        assertEquals(TraceCopy.DEFAULT_BLINK_RATE, TraceCopy.blinkRate(-1));
        assertEquals(TraceCopy.DEFAULT_BLINK_RATE, TraceCopy.blinkRate("fast"));
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
