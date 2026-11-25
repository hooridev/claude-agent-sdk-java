package dev.hoori.agents.claude.transport;

import java.util.Iterator;
import java.util.Map;

/**
 * Low-level raw I/O with a Claude Code process or service. The SDK builds the control protocol
 * on top of it. Implement this for custom connections (e.g. a remote Claude Code).
 *
 * <p>Like in the Python SDK this is an internal-facing API that may change between releases.
 * Implementations must be safe for {@link #write} to be called from several threads.
 */
public interface Transport extends AutoCloseable {

    /** Starts the process / opens the connection. */
    void connect();

    /** Writes raw data (typically one JSON object plus a newline). */
    void write(String data);

    /**
     * Returns the parsed incoming messages. {@code hasNext()} blocks until a message arrives and
     * returns {@code false} at end of stream; errors surface as unchecked exceptions.
     * Called once per connection.
     */
    Iterator<Map<String, Object>> readMessages();

    /** Ends the input stream (closes stdin for process transports). */
    void endInput();

    /** Whether the transport is ready for communication. */
    boolean isReady();

    /** Closes the transport and cleans up. Must not block forever. */
    @Override
    void close();
}
