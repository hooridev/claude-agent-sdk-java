package dev.hoori.agents.claude.transport;

import java.io.IOException;
import java.io.Reader;

/**
 * Reads newline-terminated lines while bounding the size of a single line, so a producer that
 * never writes a newline cannot grow memory without limit (Python: {@code _LineFramer} + guard).
 */
final class BoundedLineReader {

    /** Thrown when a line exceeds the limit and overflow is not tolerated. */
    static final class LineTooLongException extends IOException {
        LineTooLongException(int limit) {
            super("JSON message exceeded maximum buffer size of " + limit + " bytes");
        }
    }

    private final Reader reader;
    private final int maxLineLength;
    private final boolean emitOverflowAsLine;
    private final char[] buffer = new char[8192];
    private int position;
    private int limit;

    /**
     * @param emitOverflowAsLine if {@code true}, an over-long line is returned in pieces of
     *                           {@code maxLineLength} instead of failing (used for stderr)
     */
    BoundedLineReader(Reader reader, int maxLineLength, boolean emitOverflowAsLine) {
        this.reader = reader;
        this.maxLineLength = maxLineLength;
        this.emitOverflowAsLine = emitOverflowAsLine;
    }

    /**
     * @return the next line without its terminator, or {@code null} at end of stream
     * @throws LineTooLongException if a line exceeds the limit and overflow is not tolerated
     */
    String readLine() throws IOException {
        StringBuilder line = null;
        while (true) {
            if (position >= limit) {
                limit = reader.read(buffer, 0, buffer.length);
                position = 0;
                if (limit <= 0) {
                    limit = 0;
                    return line == null ? null : line.toString();
                }
            }
            if (line == null) {
                line = new StringBuilder();
            }
            int start = position;
            while (position < limit && buffer[position] != '\n') {
                position++;
            }
            line.append(buffer, start, position - start);
            boolean complete = position < limit;
            if (complete) {
                position++; // consume '\n'
            }
            if (line.length() > maxLineLength) {
                if (emitOverflowAsLine) {
                    return line.toString();
                }
                throw new LineTooLongException(maxLineLength);
            }
            if (complete) {
                return line.toString();
            }
        }
    }
}
