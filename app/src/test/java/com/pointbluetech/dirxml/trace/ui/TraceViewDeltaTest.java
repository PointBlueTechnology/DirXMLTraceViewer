package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.RawTraceEvent;
import com.pointbluetech.dirxml.trace.model.TimestampFormat;
import com.pointbluetech.dirxml.trace.model.TraceParser;
import com.pointbluetech.dirxml.trace.model.TraceTime;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceViewDeltaTest {

    @Test
    void rendersElapsedTimeFromFirstReceivedRecordEvenWhenItIsNotDisplayed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TraceParser parser = new TraceParser();
            parser.setTimestampFormat(TimestampFormat.DELTA);
            TraceView view = new TraceView(100);
            view.setAutoScroll(false);
            view.setTimestampFormat(TimestampFormat.DELTA);
            TraceEntry first = new TraceEntry(0, parser.parse(event(1_000, "first")));
            TraceEntry second = new TraceEntry(1, parser.parse(event(2_234, "second")));
            TraceEntry third = new TraceEntry(2, parser.parse(event(4_001, "third")));
            view.setDeltaBaseAt(TraceTime.timestamp(first.record));

            view.append(List.of(second, third));

            String text = view.textPane().getText();
            assertTrue(text.contains("[1.234]:AD ST:second"), text);
            assertTrue(text.contains("[3.001]:AD ST:third"), text);
        });
    }

    @Test
    void calculatesIntervalsForTraceFileTimestamps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TraceParser parser = new TraceParser();
            TraceView view = new TraceView(100);
            view.setAutoScroll(false);
            view.setTimestampFormat(TimestampFormat.DELTA);
            TraceEntry first = new TraceEntry(0,
                    parser.parseText("", "[10/14/26 10:00:00.000]:AD ST:first"));
            TraceEntry second = new TraceEntry(1,
                    parser.parseText("", "[10/14/26 10:00:01.234]:AD ST:second"));
            TraceEntry third = new TraceEntry(2,
                    parser.parseText("", "[10/15/26 10:00:02.234]:AD ST:third"));

            view.setDeltaBaseAt(TraceTime.timestamp(first.record));
            view.append(List.of(first, second, third));

            String text = view.textPane().getText();
            assertTrue(text.contains("[0.000]:AD ST:first"), text);
            assertTrue(text.contains("[1.234]:AD ST:second"), text);
            assertTrue(text.contains("[86402.234]:AD ST:third"), text);
        });
    }

    private static RawTraceEvent event(long receivedAt, String body) {
        return new RawTraceEvent(214, receivedAt, "", "%3CAD ST:" + body, List.of());
    }
}
