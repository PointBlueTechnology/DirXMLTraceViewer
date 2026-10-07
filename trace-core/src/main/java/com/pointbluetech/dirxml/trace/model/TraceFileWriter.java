package com.pointbluetech.dirxml.trace.model;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends trace records to a text file as they are displayed. Color directives are not written. */
public final class TraceFileWriter implements Closeable {

    private final Path path;
    private final BufferedWriter out;
    private long records;

    public TraceFileWriter(Path path, boolean append) throws IOException {
        this.path = path;
        this.out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void write(TraceRecord record) throws IOException {
        write(record, false);
    }

    /** @param tagServer prefix message headers with {@code [server] } (when tracing several servers) */
    public void write(TraceRecord record, boolean tagServer) throws IOException {
        if (tagServer && record.header() != null && !record.server().isEmpty()) {
            out.write("[" + record.server() + "] ");
        }
        out.write(record.text().replace("\r\n", "\n").replace("\n", System.lineSeparator()));
        records++;
    }

    public void flush() throws IOException {
        out.flush();
    }

    public Path path() {
        return path;
    }

    public long records() {
        return records;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
