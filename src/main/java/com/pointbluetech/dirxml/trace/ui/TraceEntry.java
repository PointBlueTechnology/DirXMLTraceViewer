package com.pointbluetech.dirxml.trace.ui;

import com.pointbluetech.dirxml.trace.model.TraceCompactor;
import com.pointbluetech.dirxml.trace.model.TraceHighlighter;
import com.pointbluetech.dirxml.trace.model.TraceRecord;

import java.util.List;

/**
 * A stored trace record plus its display form (text and style runs), computed once and cached so
 * appending and rebuilding the view never re-highlights on the event thread. Thread-safe: renders
 * may be computed on any thread.
 */
final class TraceEntry {

    static final int TOKEN_COUNT = TraceHighlighter.Token.values().length;

    /**
     * Display text with parallel run arrays. A style code below {@link #TOKEN_COUNT} is a token
     * ordinal; otherwise it is {@code TOKEN_COUNT + DSTrace console color}.
     */
    record Rendered(String text, int[] starts, int[] ends, int[] styles, boolean compact, boolean tagServer) {
    }

    final long seq;
    final TraceRecord record;
    private volatile Rendered rendered;

    TraceEntry(long seq, TraceRecord record) {
        this.seq = seq;
        this.record = record;
    }

    Rendered render(boolean compact, boolean tagServer) {
        Rendered r = rendered;
        if (r != null && r.compact() == compact && r.tagServer() == tagServer) {
            return r;
        }
        r = build(compact, tagServer);
        rendered = r;
        return r;
    }

    private Rendered build(boolean compact, boolean tagServer) {
        TraceRecord rec = compact ? TraceCompactor.compact(record) : record;
        String tag = tagServer && rec.header() != null && !rec.server().isEmpty() ? "[" + rec.server() + "] " : "";
        List<TraceHighlighter.Run> runs = TraceHighlighter.highlight(rec);
        int extra = tag.isEmpty() ? 0 : 1;
        int[] starts = new int[runs.size() + extra];
        int[] ends = new int[starts.length];
        int[] styles = new int[starts.length];
        if (extra == 1) {
            starts[0] = 0;
            ends[0] = tag.length();
            styles[0] = TraceHighlighter.Token.THREAD_PUB.ordinal();
        }
        int shift = tag.length();
        for (int i = 0; i < runs.size(); i++) {
            TraceHighlighter.Run run = runs.get(i);
            starts[i + extra] = run.start() + shift;
            ends[i + extra] = run.end() + shift;
            styles[i + extra] = run.token() == TraceHighlighter.Token.DS_COLOR ? TOKEN_COUNT + run.dsColor() : run.token().ordinal();
        }
        return new Rendered(tag + rec.text(), starts, ends, styles, compact, tagServer);
    }
}
