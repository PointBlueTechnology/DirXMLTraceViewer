package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.TraceMarker;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceTime;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #2 in the desktop view: next, previous, most recent and go-to-time land on the right record. */
class TraceViewJumpTest {

    private static final String[] LINES = {
            "[10/14/26 10:00:00.000]:AD ST:Start transaction.\n",
            "[10/14/26 10:00:01.000]:AD ST:Applying policy: one.\n",
            "[10/14/26 10:00:02.000]:AD ST:End transaction.\n",
            "[10/14/26 10:05:00.000]:AD ST:Start transaction.\n",
            "  <nds/>\n",
            "[10/14/26 10:05:01.000]:AD ST:Applying policy: two.\n",
            "[10/14/26 10:05:02.000]:AD ST:End transaction.\n",
    };

    private int offsetOf(int line) {
        int o = 0;
        for (int i = 0; i < line; i++) o += LINES[i].length();
        return o;
    }

    @Test
    void jumpsBetweenMarkersAndToATime() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TraceView view = new TraceView(1000);
            view.setAutoScroll(false);
            TraceParser parser = new TraceParser();
            List<TraceEntry> entries = new ArrayList<>();
            for (int i = 0; i < LINES.length; i++) {
                entries.add(new TraceEntry(i, parser.parseText("", LINES[i])));
            }
            view.append(entries);
            var pane = view.textPane();

            assertTrue(view.jump(TraceMarker.POLICY, 1));
            assertEquals(offsetOf(1), pane.getCaretPosition());
            assertTrue(view.jump(TraceMarker.POLICY, 1));
            assertEquals(offsetOf(5), pane.getCaretPosition());
            assertFalse(view.jump(TraceMarker.POLICY, 1), "no later policy");
            assertTrue(view.jump(TraceMarker.POLICY, -1));
            assertEquals(offsetOf(1), pane.getCaretPosition());
            assertTrue(view.jump(TraceMarker.END_TRANSACTION, 0), "most recent");
            assertEquals(offsetOf(6), pane.getCaretPosition());
            assertFalse(view.jump(TraceMarker.DRIVER_START, 0), "none of that kind");

            assertTrue(view.goToTime(TraceTime.parse("10:04")));
            assertEquals(offsetOf(3), pane.getCaretPosition());
            assertTrue(view.goToTime(TraceTime.parse("10:05:00.500")), "a continuation takes its predecessor's time");
            assertEquals(offsetOf(5), pane.getCaretPosition());
            assertFalse(view.goToTime(TraceTime.parse("11:00")));
        });
    }
}
