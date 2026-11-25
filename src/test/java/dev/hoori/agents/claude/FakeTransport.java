package dev.hoori.agents.claude;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.transport.Transport;

/**
 * In-memory stand-in for the CLI. Answers {@code initialize} automatically and lets tests emit
 * frames and react to what the SDK writes.
 */
public class FakeTransport implements Transport {

    /** Reacts to a message written by the SDK; may emit frames via the transport. */
    @FunctionalInterface
    public interface Responder {
        void onWrite(Map<String, Object> message, FakeTransport cli);
    }

    private final LinkedBlockingQueue<Optional<Map<String, Object>>> toSdk = new LinkedBlockingQueue<>();
    public final List<Map<String, Object>> written = new CopyOnWriteArrayList<>();
    public final CountDownLatch stdinClosed = new CountDownLatch(1);
    private final List<Responder> responders = new CopyOnWriteArrayList<>();
    public volatile Map<String, Object> initializeResponse = Map.of();
    private volatile boolean ready;
    public volatile boolean closed;

    public FakeTransport() {
        responders.add((message, cli) -> {
            if (isControlRequest(message, "initialize")) {
                cli.respondSuccess(message, initializeResponse);
            }
        });
    }

    public FakeTransport onWrite(Responder responder) {
        responders.add(responder);
        return this;
    }

    /** A responder answering every user message with an assistant text and a success result. */
    public FakeTransport answeringPrompts(String reply) {
        return onWrite((message, cli) -> {
            if ("user".equals(message.get("type"))) {
                cli.emit(assistant(reply));
                cli.emit(result(false));
            }
        });
    }

    public static boolean isControlRequest(Map<String, Object> message, String subtype) {
        return "control_request".equals(message.get("type"))
                && subtype.equals(Json.object(message, "request").get("subtype"));
    }

    public void respondSuccess(Map<String, Object> request, Map<String, Object> payload) {
        emit(Map.of("type", "control_response", "response",
                Map.of("subtype", "success", "request_id", request.get("request_id"), "response", payload)));
    }

    public void respondError(Map<String, Object> request, String error) {
        emit(Map.of("type", "control_response", "response",
                Map.of("subtype", "error", "request_id", request.get("request_id"), "error", error)));
    }

    public void emit(Map<String, Object> frame) {
        toSdk.add(Optional.of(frame));
    }

    /** Ends stdout. */
    public void finish() {
        toSdk.add(Optional.empty());
    }

    /** Blocks until a written message matches, or fails after 5 s. */
    public Map<String, Object> awaitWritten(Predicate<Map<String, Object>> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            for (Map<String, Object> message : written) {
                if (predicate.test(message)) {
                    return message;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No matching message written; got " + written);
    }

    public List<Map<String, Object>> writtenOfType(String type) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> message : written) {
            if (type.equals(message.get("type"))) {
                result.add(message);
            }
        }
        return result;
    }

    @Override
    public void connect() {
        ready = true;
    }

    @Override
    public void write(String data) {
        Map<String, Object> message = Json.parseObject(data.strip());
        written.add(message);
        for (Responder responder : responders) {
            responder.onWrite(message, this);
        }
    }

    @Override
    public Iterator<Map<String, Object>> readMessages() {
        return new Iterator<>() {
            private Map<String, Object> next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next == null && !done) {
                    try {
                        Optional<Map<String, Object>> item = toSdk.take();
                        if (item.isEmpty()) {
                            done = true;
                        } else {
                            next = item.get();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        done = true;
                    }
                }
                return next != null;
            }

            @Override
            public Map<String, Object> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Map<String, Object> result = next;
                next = null;
                return result;
            }
        };
    }

    @Override
    public void endInput() {
        stdinClosed.countDown();
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void close() {
        closed = true;
        ready = false;
        finish();
    }

    // ------------------------------------------------------------------ frame factories

    public static Map<String, Object> assistant(String text) {
        return Map.of("type", "assistant", "message", Map.of("model", "claude-test", "content",
                List.of(Map.of("type", "text", "text", text))));
    }

    public static Map<String, Object> result(boolean isError) {
        return Map.of("type", "result", "subtype", isError ? "error_max_turns" : "success", "duration_ms", 10,
                "duration_api_ms", 5, "is_error", isError, "num_turns", 1, "session_id", "s1",
                "result", isError ? "" : "done");
    }

    public static Map<String, Object> sessionState(String state) {
        return Map.of("type", "system", "subtype", "session_state_changed", "state", state,
                "sdk_host_only", true);
    }

    public static Map<String, Object> controlRequest(String requestId, Map<String, Object> request) {
        return Map.of("type", "control_request", "request_id", requestId, "request", request);
    }
}
