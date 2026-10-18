package com.pointbluetech.dirxml.trace.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issues #2 (jump markers) and #4 (rule-name and JSON highlighting), on synthetic trace lines. */
class MarkersAndHighlightTest {

    private static TraceRecord rec(String text) {
        return new TraceParser().parseText("", text);
    }

    private static TraceMarker marker(String text) {
        return TraceMarker.of(rec(text));
    }

    @Test
    void recognisesEachMarkerByTheEnginesWording() {
        assertEquals(TraceMarker.DRIVER_START, marker("[10/14/26 10:15:12.345]:Loopback :Reading driver information from the \\T\\system\\ds\\Loopback object.\n"));
        assertEquals(TraceMarker.PUBLISHER_EVENT, marker("[10/14/26 10:15:12.345]:AD PT:Receiving DOM document from application.\n<nds/>\n"));
        assertEquals(TraceMarker.SUBSCRIBER_EVENT, marker("[10/14/26 10:15:12.345]:AD ST:Start transaction.\n"));
        assertEquals(TraceMarker.SUBSCRIBER_EXECUTE, marker("[10/14/26 10:15:12.345]:AD ST:Submitting document to subscriber shim:\n<nds/>\n"));
        assertEquals(TraceMarker.POLICY_SET, marker("[10/14/26 10:15:12.345]:AD ST:Applying schema mapping policies to output.\n"));
        assertEquals(TraceMarker.POLICY_SET, marker("[10/14/26 10:15:12.345]:AD PT:Applying input transformation policies.\n"));
        assertEquals(TraceMarker.POLICY, marker("[10/14/26 10:15:12.345]:AD ST:Applying policy: sub-ctp-Example.\n"));
        assertEquals(TraceMarker.END_TRANSACTION, marker("[10/14/26 10:15:12.345]:AD ST:End transaction.\n"));
        assertEquals(TraceMarker.DIRXML_LOG_EVENT, marker("[10/14/26 10:15:12.345]:AD ST:\nDirXML Log Event -------------------\n     Driver:   \\T\\system\\ds\\AD\n"));
        assertEquals(TraceMarker.PUMP_XDS, marker("[10/14/26 10:15:12.345]:AD PT:Pumping XDS to eDirectory.\n"));
    }

    @Test
    void otherMessagesAreNoMarker() {
        assertNull(marker("[10/14/26 10:15:12.345]:AD ST:Rule selected.\n"));
        assertNull(marker("    <status level=\"success\"/>\n"));
        assertNull(marker("[10/14/26 10:15:12.345]:AD :Applying policy: x.\n"), "a policy outside the channels");
        assertNull(marker("[10/14/26 10:15:12.345]:AD ST:Query from policy\n  End transaction.\n"), "only the first line counts");
    }

    @Test
    void readsTheTimeOfDayFromTheHeader() {
        assertEquals(((10 * 60 + 15) * 60 + 12) * 1000L + 345, TraceTime.timeOfDay(rec("[10/14/26 10:15:12.345]:AD ST:x\n")));
        assertEquals(((10 * 60 + 15) * 60 + 12) * 1000L + 345, TraceTime.timeOfDay(rec("[2026-10-14 10:15:12.345]:AD ST:x\n")));
        assertEquals(-1, TraceTime.timeOfDay(rec("  <nds/>\n")));
        assertEquals((9 * 60 + 5) * 60_000L, TraceTime.parse("9:05"));
        assertEquals(((9 * 60 + 5) * 60 + 7) * 1000L + 500, TraceTime.parse("09:05:07.5"));
        assertEquals(-1, TraceTime.parse("25:00"));
        assertEquals(-1, TraceTime.parse("soon"));
    }

    private static String tokens(String text, TraceHighlighter.Token t) {
        TraceRecord r = rec(text);
        return TraceHighlighter.highlight(r).stream().filter(x -> x.token() == t)
                .map(x -> r.text().substring(x.start(), x.end())).collect(Collectors.joining("|"));
    }

    @Test
    void highlightsRuleNamesWithoutTheirPeriod() {
        assertEquals("Veto if no CN (v1.2)", tokens("[10/14/26 10:15:12.345]:AD ST:Evaluating selection criteria for rule 'Veto if no CN (v1.2)'.\n".replace("'", ""), TraceHighlighter.Token.RULE_NAME));
        assertEquals("Set the department", tokens("[10/14/26 10:15:12.345]:AD ST:Applying rule Set the department.\n", TraceHighlighter.Token.RULE_NAME));
        assertEquals("", tokens("[10/14/26 10:15:12.345]:AD ST:Rule rejected.\n", TraceHighlighter.Token.RULE_NAME));
    }

    @Test
    void highlightsJsonAndLeavesTheRestAlone() {
        String text = "[10/14/26 10:15:12.345]:REST ST:Response body:\n{\n  \"id\": \"a-1\",\n  \"active\": true,\n  \"n\": -1.5e3,\n  \"tags\": [\"x\", null]\n}\n";
        assertEquals("\"id\"|\"active\"|\"n\"|\"tags\"", tokens(text, TraceHighlighter.Token.JSON_KEY));
        assertEquals("\"a-1\"|\"x\"", tokens(text, TraceHighlighter.Token.JSON_STRING));
        assertEquals("-1.5e3", tokens(text, TraceHighlighter.Token.JSON_NUMBER));
        assertEquals("true|null", tokens(text, TraceHighlighter.Token.JSON_LITERAL));
        assertTrue(tokens(text, TraceHighlighter.Token.JSON_PUNCT).startsWith("{|:"));
        assertEquals("", tokens("[10/14/26 10:15:12.345]:AD ST:set {\"a\": } [1, 2\n", TraceHighlighter.Token.JSON_KEY),
                "not JSON: the header's brackets, a malformed object");
    }

    @Test
    void everyRecordIsStillCoveredByRuns() {
        TraceRecord r = rec("[10/14/26 10:15:12.345]:REST ST:{\"a\":[1,{\"b\":false}]} and Applying rule R.\n");
        List<TraceHighlighter.Run> runs = TraceHighlighter.highlight(r);
        assertEquals(0, runs.get(0).start());
        assertEquals(r.text().length(), runs.get(runs.size() - 1).end());
        for (int i = 1; i < runs.size(); i++) {
            assertEquals(runs.get(i - 1).end(), runs.get(i).start());
        }
    }
}
