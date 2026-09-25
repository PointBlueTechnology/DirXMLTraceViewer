package com.pointbluetech.dirxml.trace.model;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceModelTest {

    private static RawTraceEvent event(String format, Object... params) {
        return new RawTraceEvent(214, 0, "", format, List.of(params));
    }

    @Test
    void substitutesTypedParameters() {
        FormattedText t = DsTraceFormatter.format("%s has %d items (0x%x) %5s|%-3d| 100%%", List.of("AD", 3, 255, "ab", 7));
        assertEquals("AD has 3 items (0xff)    ab|7  | 100%", t.text());
    }

    @Test
    void keepsConversionsWithoutParameters() {
        assertEquals("value %s", DsTraceFormatter.format("value %s", List.of()).text());
    }

    @Test
    void turnsColorDirectivesIntoSpans() {
        FormattedText t = DsTraceFormatter.format("Applying policy: %+C%14Csub-ctp%-C.", List.of());
        assertEquals("Applying policy: sub-ctp.", t.text());
        assertEquals(List.of(new FormattedText.ColorSpan(17, 24, 14)), t.colorSpans());
    }

    @Test
    void nestedColorsRestoreOuterColor() {
        FormattedText t = DsTraceFormatter.format("%+C%11Ca%+C%12Cb%-Cc%-Cd", List.of());
        assertEquals("abcd", t.text());
        assertEquals(List.of(new FormattedText.ColorSpan(0, 1, 11), new FormattedText.ColorSpan(1, 2, 12),
                new FormattedText.ColorSpan(2, 3, 11)), t.colorSpans());
    }

    @Test
    void parsesHeaderAndInheritsItForContinuations() {
        TraceParser p = new TraceParser();
        TraceRecord header = p.parse(event("[09/25/26 10:15:12.345]:HR JDBC PT:Polling for changes."));
        assertEquals("HR JDBC", header.driverName());
        assertEquals(Channel.PUBLISHER, header.channel());
        assertNotNull(header.header());
        assertEquals("[09/25/26 10:15:12.345]", header.text().substring(header.header().timestampStart(), header.header().timestampEnd()));

        TraceRecord body = p.parse(event("<nds>\n</nds>"));
        assertNull(body.header());
        assertEquals("HR JDBC", body.driverName());
        assertEquals(Channel.PUBLISHER, body.channel());
        assertTrue(body.text().endsWith("\n"));

        TraceRecord sub = p.parse(event("[09/25/26 10:15:13.000]:AD ST:Start transaction."));
        assertEquals("AD", sub.driverName());
        assertEquals(Channel.SUBSCRIBER, sub.channel());

        TraceRecord engine = p.parse(event("[09/25/26 10:15:13.000]:AD :Driver starting."));
        assertEquals("AD", engine.driverName());
        assertEquals(Channel.OTHER, engine.channel());
    }

    @Test
    void filtersByDriverChannelAndText() {
        TraceParser p = new TraceParser();
        TraceRecord ad = p.parse(event("[09/25/26 10:15:13.000]:AD ST:Start transaction."));
        TraceRecord hr = p.parse(event("[09/25/26 10:15:13.000]:HR PT:Polling."));

        TraceFilter onlyAd = TraceFilter.ALL.withDrivers(Set.of("ad"), Set.of());
        assertTrue(onlyAd.matches(ad));
        assertFalse(onlyAd.matches(hr));

        TraceFilter pubOnly = TraceFilter.ALL.withChannels(EnumSet.of(Channel.PUBLISHER));
        assertFalse(pubOnly.matches(ad));
        assertTrue(pubOnly.matches(hr));

        assertTrue(TraceFilter.ALL.withText("TRANSACTION").matches(ad));
        assertFalse(TraceFilter.ALL.withText("transaction").matches(hr));
    }

    @Test
    void filtersByPerpetratorDn() {
        TraceRecord r = new TraceParser().parse(new RawTraceEvent(217, 0, "CN=AD, cn=DS,o=system", "no header", List.of()));
        assertTrue(TraceFilter.ALL.withDrivers(Set.of("other"), Set.of("cn=ad,cn=ds,o=system")).matches(r));
    }

    @Test
    void highlightsXmlStatusAndHeader() {
        TraceRecord r = new TraceParser().parse(event(
                "[09/25/26 10:15:13.000]:AD ST:<status level=\"error\">boom</status><!-- c -->"));
        List<TraceHighlighter.Run> runs = TraceHighlighter.highlight(r);
        String text = r.text();
        assertEquals(TraceHighlighter.Token.DRIVER, tokenOf(runs, text.indexOf("AD")));
        assertEquals(TraceHighlighter.Token.THREAD_SUB, tokenOf(runs, text.indexOf("ST")));
        assertEquals(TraceHighlighter.Token.XML_TAG, tokenOf(runs, text.indexOf("status")));
        assertEquals(TraceHighlighter.Token.XML_ATTR, tokenOf(runs, text.indexOf("level")));
        assertEquals(TraceHighlighter.Token.STATUS_ERROR, tokenOf(runs, text.indexOf("error")));
        assertEquals(TraceHighlighter.Token.STATUS_ERROR, tokenOf(runs, text.indexOf("boom")));
        assertEquals(TraceHighlighter.Token.XML_COMMENT, tokenOf(runs, text.indexOf("<!--")));
        // runs cover the text exactly
        assertEquals(0, runs.getFirst().start());
        assertEquals(text.length(), runs.getLast().end());
    }

    @Test
    void engineColorsOverrideXml() {
        TraceRecord r = new TraceParser().parse(event("Applying policy: %+C%14Cmy-policy%-C."));
        List<TraceHighlighter.Run> runs = TraceHighlighter.highlight(r);
        TraceHighlighter.Run run = runs.stream().filter(x -> x.token() == TraceHighlighter.Token.DS_COLOR).findFirst().orElseThrow();
        assertEquals("my-policy", r.text().substring(run.start(), run.end()));
        assertEquals(14, run.dsColor());
    }

    @Test
    void continuationsFollowTheirOwnServersHeader() {
        TraceParser p = new TraceParser();
        p.parse(new RawTraceEvent("idm1", 214, 0, "", "[09/25/26 10:00:00.000]:AD ST:Received", List.of()));
        p.parse(new RawTraceEvent("idm2", 214, 0, "", "[09/25/26 10:00:00.001]:HR PT:Polling", List.of()));
        TraceRecord body = p.parse(new RawTraceEvent("idm1", 214, 0, "", "<nds/>", List.of()));
        assertEquals("AD", body.driverName());
        assertEquals(Channel.SUBSCRIBER, body.channel());
        assertEquals("idm1", body.server());
    }

    @Test
    void ldapEventsWithoutTimestampAreStampedAndAttributed() {
        TraceParser p = new TraceParser();
        TraceRecord pub = p.parse(event("%3CIG Update PT:Applying policy: %14CSet Assocation%3C."));
        assertEquals("IG Update", pub.driverName());
        assertEquals(Channel.PUBLISHER, pub.channel());
        assertNotNull(pub.header());
        assertTrue(pub.text().matches("(?s)\\[\\d\\d/\\d\\d/\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3}\\]:IG Update PT:.*"));
        FormattedText.ColorSpan policy = pub.colorSpans().stream().filter(c -> c.color() == 14).findFirst().orElseThrow();
        assertEquals("Set Assocation", pub.text().substring(policy.start(), policy.end()));

        TraceRecord xml = p.parse(event("%3CAD ST:\n<nds dtdversion=\"4.0\">\n</nds>"));
        assertEquals("AD", xml.driverName());
        assertEquals(Channel.SUBSCRIBER, xml.channel());

        TraceRecord driverLevel = p.parse(event("%3CAD :Remote Interface Driver: Connection closed"));
        assertEquals("AD", driverLevel.driverName());
        assertEquals(Channel.OTHER, driverLevel.channel());

        TraceRecord service = p.parse(event("%3CAD SST:    Rule rejected."));
        assertEquals("AD", service.driverName());
        assertEquals(Channel.SERVICE, service.channel());
        assertEquals(TraceHighlighter.Token.THREAD_SVC, tokenOf(TraceHighlighter.highlight(service), service.header().threadStart()));

        TraceRecord fromFile = p.parse(event("[03/06/26 22:40:29.151]:AD SST:    Rule rejected."));
        assertEquals(Channel.SERVICE, fromFile.channel());
    }

    @Test
    void xmlAndIndentedTextAreNotHeaders() {
        TraceParser p = new TraceParser();
        p.parse(event("[09/25/26 10:15:12.345]:HR JDBC PT:Sending:"));
        assertNull(p.parse(event("<nds xmlns:x=\"y\">")).header());
        assertNull(p.parse(event("  Applying to status #1 PT:")).header());
    }

    @Test
    void compactingDropsBlankLinesAndKeepsHighlightingAligned() {
        TraceRecord r = new TraceParser().parse(event(
                "[09/25/26 13:56:22.804]:AD PT:Applying XSLT policy: %14Cpol%3C.\nProcessing source node /\n\n\n   Instantiating rule 0\n\n\n\n      Instantiating copy\n"));
        TraceRecord c = TraceCompactor.compact(r);
        assertEquals("[09/25/26 13:56:22.804]:AD PT:Applying XSLT policy: pol.\nProcessing source node /\n"
                + "   Instantiating rule 0\n      Instantiating copy\n", c.text());
        assertEquals("AD", c.text().substring(c.header().nameStart(), c.header().nameEnd()));
        FormattedText.ColorSpan pol = c.colorSpans().stream().filter(x -> x.color() == 14).findFirst().orElseThrow();
        assertEquals("pol", c.text().substring(pol.start(), pol.end()));
        TraceHighlighter.highlight(c); // offsets must stay within the text
    }

    @Test
    void readsTraceFileIntoMessages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path f = dir.resolve("ad.trace");
        java.nio.file.Files.writeString(f, String.join("\n",
                "[03/06/26 22:40:29.100]:AD ST:Received event from eDirectory.",
                "<nds dtdversion=\"4.0\">",
                "  <input value=\"100%d\"/>",
                "</nds>",
                "[03/06/26 22:40:29.151]:AD SST:    Rule rejected.",
                "[03/06/26 22:40:29.200]:AD PT:Polling.",
                ""));
        List<TraceRecord> records = new java.util.ArrayList<>();
        assertTrue(TraceFileReader.read(f, records::add, b -> { }, () -> true));
        assertEquals(3, records.size());
        assertEquals(Channel.SUBSCRIBER, records.get(0).channel());
        assertTrue(records.get(0).text().contains("100%d"), "literal % must survive");
        assertTrue(records.get(0).text().endsWith("</nds>\n"));
        assertEquals(Channel.SERVICE, records.get(1).channel());
        assertEquals(Channel.PUBLISHER, records.get(2).channel());
        assertEquals("AD", records.get(2).driverName());
    }

    private static TraceHighlighter.Token tokenOf(List<TraceHighlighter.Run> runs, int pos) {
        return runs.stream().filter(r -> r.start() <= pos && pos < r.end()).findFirst().orElseThrow().token();
    }
}
