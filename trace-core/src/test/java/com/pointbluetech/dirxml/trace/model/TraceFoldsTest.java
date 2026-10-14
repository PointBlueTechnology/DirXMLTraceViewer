package com.pointbluetech.dirxml.trace.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #3, F1 of docs/folding.md: the regions, on synthetic trace (one record per line, as a live stream sends them). */
class TraceFoldsTest {

    private final TraceParser parser = new TraceParser();
    private final TraceFolds folds = new TraceFolds();

    private TraceFolds.Placement add(String text) {
        return folds.add(parser.parseText("", text));
    }

    private static String h(String driverAndTag, String body) {
        return "[10/14/26 10:00:00.000]:" + driverAndTag + ":" + body + "\n";
    }

    private static TraceFolds.Region only(TraceFolds.Placement p) {
        assertEquals(1, p.opens().size(), "opens one region");
        return p.opens().get(0);
    }

    @Test
    void aSetHoldsItsPoliciesAndEachPolicyItsReturnedDocument() {
        TraceFolds.Region set = only(add(h("AD ST", "Applying event transformation policies.")));
        assertEquals(TraceFolds.Kind.POLICY_SET, set.kind());
        assertEquals("Applying event transformation policies.", set.label());
        assertEquals(-1, set.parent());

        TraceFolds.Placement p1 = add(h("AD ST", "Applying policy: sub-etp-One."));
        TraceFolds.Region one = only(p1);
        assertEquals(TraceFolds.Kind.POLICY, one.kind());
        assertEquals("sub-etp-One", one.label());
        assertEquals(set.id(), one.parent());
        assertEquals(set.id(), p1.within(), "an opener sits in its region's parent");

        assertEquals(one.id(), add(h("AD ST", "  Evaluating selection criteria for rule Veto.")).within());
        assertEquals(one.id(), add(h("AD ST", "Policy returned:")).within());
        TraceFolds.Placement doc = add("<nds dtdversion=\"4.0\">\n");
        TraceFolds.Region xml = only(doc);
        assertEquals(TraceFolds.Kind.XML, xml.kind());
        assertEquals("<nds>", xml.label());
        assertEquals(one.id(), xml.parent());
        assertEquals(xml.id(), add("  <input/>\n").within());
        assertEquals(xml.id(), add("</nds>\n").within());

        TraceFolds.Region two = only(add(h("AD ST", "Applying policy: sub-etp-Two.")));
        assertEquals(set.id(), two.parent(), "the next policy closes the last and stays in the set");
        add(h("AD ST", "Policy returned:"));
        add("<nds/>\n");

        TraceFolds.Placement after = add(h("AD ST", "Applying schema mapping policies to output."));
        TraceFolds.Region next = only(after);
        assertEquals(TraceFolds.Kind.POLICY_SET, next.kind());
        assertEquals(-1, after.within(), "the next set closes this one");
    }

    @Test
    void aVetoOrTheShimClosesThePolicyAndItsSet() {
        TraceFolds.Region set = only(add(h("AD ST", "Applying command transformation policies.")));
        TraceFolds.Region pol = only(add(h("AD ST", "Applying policy: sub-ctp-Veto.")));
        assertEquals(pol.id(), add(h("AD ST", "  Applying rule Veto if no surname.")).within());
        assertEquals(pol.id(), add(h("AD ST", "Policy returned:")).within());
        add("<nds><input/></nds>\n");
        TraceFolds.Placement end = add(h("AD ST", "End transaction."));
        assertEquals(-1, end.within(), "the end of the transaction is outside both");
        assertTrue(end.opens().isEmpty());

        only(add(h("AD ST", "Applying output transformation policies.")));
        add(h("AD ST", "Applying policy: sub-otp-X."));
        TraceFolds.Placement shim = add(h("AD ST", "Submitting document to subscriber shim:"));
        assertEquals(-1, shim.within(), "a policy without its returned line still ends at the shim");
        assertTrue(set.id() >= 0);
    }

    @Test
    void aSetWithNothingInItEndsAtTheNextMessage() {
        only(add(h("AD PT", "Applying input transformation policies.")));
        assertEquals(-1, add(h("AD PT", "Pumping XDS to eDirectory.")).within());
    }

    @Test
    void interleavedDriversKeepTheirOwnRegions() {
        TraceFolds.Region adSet = only(add(h("AD ST", "Applying event transformation policies.")));
        TraceFolds.Region adPol = only(add(h("AD ST", "Applying policy: ad-etp.")));
        TraceFolds.Region lpSet = only(add(h("Loop ST", "Applying event transformation policies.")));
        assertEquals(-1, lpSet.parent(), "another driver's set is not inside AD's");
        TraceFolds.Region lpPol = only(add(h("Loop ST", "Applying policy: loop-etp.")));
        assertEquals(lpSet.id(), lpPol.parent());
        assertEquals(adPol.id(), add(h("AD ST", "  Applying rule A.")).within(), "AD's policy is still open");
        assertEquals(lpPol.id(), add(h("Loop ST", "  Applying rule B.")).within());
        assertEquals(adSet.id(), adPol.parent());
        TraceFolds.Region adPub = only(add(h("AD PT", "Applying input transformation policies.")));
        assertEquals(-1, adPub.parent(), "the publisher channel is a thread of its own");
    }

    @Test
    void xmlLinesInsideOneRecordAreFoundByDocument() {
        String file = "[10/14/26 10:00:00.000]:AD ST:Submitting document to subscriber shim:\n"
                + "<nds dtdversion=\"4.0\">\n  <input>\n    <add class-name=\"User\"/>\n  </input>\n</nds>\n"
                + "trailing words\n"
                + "<status level=\"success\"/>\n"
                + "<a>\n  <b>x</b>\n</a>\n";
        List<int[]> docs = TraceFolds.xmlLines(file);
        assertEquals(2, docs.size(), "a one-line document is not worth folding");
        assertArrayEquals(new int[] {1, 5}, docs.get(0));
        assertArrayEquals(new int[] {8, 10}, docs.get(1));
        TraceFolds.Placement p = folds.add(new TraceParser().parseText("", file));
        assertEquals(2, p.xmlLines().size());
        assertTrue(p.opens().isEmpty(), "in a file the document is in the record, not a region of records");
        assertEquals(List.of(), TraceFolds.xmlLines("[10/14/26 10:00:00.000]:AD ST:one line\n"));
        assertArrayEquals(new int[] {0, 2}, TraceFolds.xmlLines("<a>\n<b/>\n<c/>").get(0), "an unclosed document runs to the end");
    }

    @Test
    void aRegionStillOpenAtTheEndKeepsItsRecords() {
        only(add(h("AD ST", "Applying event transformation policies.")));
        TraceFolds.Region pol = only(add(h("AD ST", "Applying policy: open-ended.")));
        List<Integer> within = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            within.add(add(h("AD ST", "  Applying rule R" + i + ".")).within());
        }
        assertEquals(List.of(pol.id(), pol.id(), pol.id()), within);
    }
}
