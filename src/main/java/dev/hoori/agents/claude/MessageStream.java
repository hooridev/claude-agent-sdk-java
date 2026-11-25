package dev.hoori.agents.claude;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import dev.hoori.agents.claude.internal.MessageParser;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;

/**
 * A blocking, single-pass stream of messages. Iterate it with a for-each loop or {@link #stream()}.
 *
 * <p>Unknown message types are skipped. Errors of the CLI surface as unchecked exceptions from
 * {@code hasNext()}. Closing the stream releases what it owns (for {@link ClaudeAgent#query} the
 * CLI process); iterating to the end closes it as well.
 */
public final class MessageStream implements Iterable<Message>, AutoCloseable {

    private final Iterator<Map<String, Object>> source;
    private final Runnable onClose;
    private final boolean stopAfterResult;
    private boolean iteratorTaken;
    private boolean closed;

    MessageStream(Iterator<Map<String, Object>> source, Runnable onClose, boolean stopAfterResult) {
        this.source = source;
        this.onClose = onClose;
        this.stopAfterResult = stopAfterResult;
    }

    @Override
    public synchronized Iterator<Message> iterator() {
        if (iteratorTaken) {
            throw new IllegalStateException("A MessageStream can only be iterated once");
        }
        iteratorTaken = true;
        return new Iterator<>() {
            private Message next;
            private boolean done;

            @Override
            public boolean hasNext() {
                try {
                    while (next == null && !done) {
                        if (!source.hasNext()) {
                            finish();
                            return false;
                        }
                        next = MessageParser.parse(source.next());
                    }
                    return next != null;
                } catch (RuntimeException e) {
                    finish();
                    throw e;
                }
            }

            @Override
            public Message next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Message result = next;
                next = null;
                if (stopAfterResult && result instanceof ResultMessage) {
                    finish();
                }
                return result;
            }

            private void finish() {
                done = true;
                close();
            }
        };
    }

    /** A sequential {@link Stream} view; close it (or this) to release resources early. */
    public Stream<Message> stream() {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false)
                .onClose(this::close);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            onClose.run();
        }
    }
}
