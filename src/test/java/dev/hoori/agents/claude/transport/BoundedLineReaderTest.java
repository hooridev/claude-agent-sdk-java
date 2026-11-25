package dev.hoori.agents.claude.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;

import org.junit.jupiter.api.Test;

class BoundedLineReaderTest {

    @Test
    void readsLinesAcrossBufferBoundariesAndKeepsWhitespace() throws Exception {
        String longLine = " x".repeat(10_000);
        BoundedLineReader reader = new BoundedLineReader(new StringReader("a\n" + longLine + "\n\ntail"), 100_000, false);
        assertEquals("a", reader.readLine());
        assertEquals(longLine, reader.readLine());
        assertEquals("", reader.readLine());
        assertEquals("tail", reader.readLine());
        assertNull(reader.readLine());
    }

    @Test
    void enforcesTheLimit() throws Exception {
        BoundedLineReader strict = new BoundedLineReader(new StringReader("x".repeat(50) + "\n"), 10, false);
        assertThrows(BoundedLineReader.LineTooLongException.class, strict::readLine);
        BoundedLineReader lenient = new BoundedLineReader(new StringReader("y".repeat(20_000)), 10, true);
        assertEquals(8192, lenient.readLine().length(), "overflow is emitted in pieces");
    }
}
