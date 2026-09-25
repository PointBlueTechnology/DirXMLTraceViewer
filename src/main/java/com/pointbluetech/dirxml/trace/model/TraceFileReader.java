package com.pointbluetech.dirxml.trace.model;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Reads an IDM driver trace file (as written on the server) into {@link TraceRecord}s. A message
 * starts at a line beginning {@code [timestamp]:name TAG:}; the lines after it (XML documents,
 * log-event blocks) belong to it until the next such line.
 */
public final class TraceFileReader {

    private TraceFileReader() {
    }

    /**
     * @param onRecord  receives each message in file order
     * @param progress  receives the number of bytes read so far, now and then
     * @param keepGoing checked between messages; returning false stops reading early
     * @return true if the whole file was read
     */
    public static boolean read(Path file, Consumer<TraceRecord> onRecord, LongConsumer progress,
                               BooleanSupplier keepGoing) throws IOException {
        TraceParser parser = new TraceParser();
        long[] bytes = {0};
        InputStream counting = new FilterInputStream(Files.newInputStream(file)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) bytes[0] += n;
                return n;
            }
        };
        // Trace files are UTF-8 by default; don't fail on a stray byte in some other encoding.
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(counting, decoder), 1 << 16)) {
            StringBuilder message = new StringBuilder();
            long lastReport = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (TraceParser.isHeaderLine(line) && !message.isEmpty()) {
                    onRecord.accept(parser.parseText("", message.toString()));
                    message.setLength(0);
                    if (!keepGoing.getAsBoolean()) {
                        return false;
                    }
                    if (bytes[0] - lastReport > (1 << 20)) {
                        lastReport = bytes[0];
                        progress.accept(lastReport);
                    }
                }
                message.append(line).append('\n');
            }
            if (!message.isEmpty()) {
                onRecord.accept(parser.parseText("", message.toString()));
            }
            progress.accept(bytes[0]);
            return true;
        }
    }
}
