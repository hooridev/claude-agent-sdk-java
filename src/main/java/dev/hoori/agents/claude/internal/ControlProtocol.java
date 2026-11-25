package dev.hoori.agents.claude.internal;

import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.exception.ProcessException;
import dev.hoori.agents.claude.exception.ResultException;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.transport.Transport;

/**
 * The bidirectional control protocol on top of a {@link Transport} (Python: {@code _internal.query.Query}).
 *
 * <p>A reader thread routes every incoming frame: control responses complete pending requests,
 * control requests from the CLI (permissions, hooks, SDK MCP) are answered on their own virtual
 * threads, and everything else is queued for the consumer of {@link #receive()}.
 */
public final class ControlProtocol implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ControlProtocol.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long CLOSE_JOIN_MILLIS = 5_000;

    /** Queue items: a raw message, the end of the stream, or a failure. */
    private sealed interface Envelope {
        record Data(Map<String, Object> message) implements Envelope {
        }

        record End() implements Envelope {
        }

        record Failure(RuntimeException error) implements Envelope {
        }
    }

    private static final Envelope END = new Envelope.End();

    private final Transport transport;
    private final ProtocolSettings settings;
    private final HookRegistry hookRegistry;
    private final Map<String, ControlRequestHandler> handlers = new LinkedHashMap<>();
    private final RunLifecycle lifecycle;

    private final Map<String, CompletableFuture<Map<String, Object>>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, Thread> inflightRequests = new ConcurrentHashMap<>();
    private final Set<Thread> childThreads = ConcurrentHashMap.newKeySet();
    // shortcut: unbounded, unlike Python's 100-message buffer; a bounded queue could stall control
    // responses (and deadlock interrupt()) while the consumer is not reading.
    private final LinkedBlockingQueue<Envelope> queue = new LinkedBlockingQueue<>();
    private final AtomicLong requestCounter = new AtomicLong();

    private volatile TranscriptMirror transcriptMirror;
    private volatile boolean closed;
    /** Set once the reader is gone or the protocol closed: no control response can arrive any more. */
    private volatile boolean terminated;
    private volatile Map<String, Object> initializationResult;
    /** Payload of the latest {@code result} with {@code is_error}, to replace the generic exit-code error. */
    private volatile Map<String, Object> lastErrorResult;
    private Thread readerThread;

    public ControlProtocol(Transport transport, ProtocolSettings settings) {
        this.transport = transport;
        this.settings = settings;
        this.hookRegistry = new HookRegistry(settings.hooks());
        register(new PermissionRequestHandler(settings.canUseTool()));
        register(new HookCallbackHandler(hookRegistry));
        register(new McpMessageHandler(settings.sdkMcpServers()));
        boolean bidirectional = !settings.sdkMcpServers().isEmpty() || !settings.hooks().isEmpty()
                || settings.canUseTool() != null;
        this.lifecycle = new RunLifecycle(bidirectional, settings.runEndCeilingMs());
    }

    private void register(ControlRequestHandler handler) {
        handlers.put(handler.subtype(), handler);
    }

    /** Attaches a batcher receiving {@code transcript_mirror} frames (they are not passed to consumers). */
    public void setTranscriptMirror(TranscriptMirror mirror) {
        this.transcriptMirror = mirror;
    }

    /** Surfaces a failed {@code SessionStore.append} as a {@code mirror_error} system message. */
    public void reportMirrorError(Map<String, Object> key, String error) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "system");
        message.put("subtype", "mirror_error");
        message.put("error", error);
        message.put("key", key);
        message.put("uuid", UUID.randomUUID().toString());
        message.put("session_id", key != null && key.get("session_id") != null ? key.get("session_id") : "");
        queue.add(new Envelope.Data(message));
    }

    // ------------------------------------------------------------------ lifecycle

    /** Starts the reader thread. */
    public synchronized void start() {
        if (readerThread == null) {
            readerThread = Thread.ofVirtual().name("claude-reader").start(this::readLoop);
        }
    }

    /**
     * Sends the initialize request (hooks, agents, skills, ...).
     *
     * @return the CLI's initialize response
     */
    public Map<String, Object> initialize() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "initialize");
        request.put("hooks", hookRegistry.initializeConfig());
        if (!settings.agents().isEmpty()) {
            request.put("agents", settings.agents());
        }
        if (settings.excludeDynamicSections() != null) {
            request.put("excludeDynamicSections", settings.excludeDynamicSections());
        }
        if (settings.systemPromptSnapshot() != null) {
            request.put("systemPromptSnapshot", settings.systemPromptSnapshot());
        }
        // "all" and omitted are equivalent on the wire (no filter); only an explicit list is sent.
        if (settings.skills() != null) {
            request.put("skills", settings.skills());
        }
        if (settings.forwardSubagentText()) {
            request.put("forwardSubagentText", true);
        }
        Map<String, Object> response = sendControlRequest(request, settings.initializeTimeoutMillis());
        if (hookRegistry.blocksOnFailure()) {
            // A guard meant to fail closed would otherwise silently fail open.
            if (!Json.isTrue(response, "hook_on_failure_supported")) {
                throw new CliConnectionException("The process answering initialize does not honor onFailure on "
                        + "hook matchers, so a hook that fails would not block. Update Claude Code, or remove "
                        + "HookFailureMode.BLOCK from your hooks.");
            }
            if (Boolean.FALSE.equals(response.get("hooks_applied"))) {
                throw new CliConnectionException("This session was already running and did not register your "
                        + "hooks, so a hook set to HookFailureMode.BLOCK would never run. Start a new session, or "
                        + "remove HookFailureMode.BLOCK from your hooks.");
            }
        }
        initializationResult = response;
        return response;
    }

    /** The initialize response, or {@code null} before {@link #initialize()}. */
    public Map<String, Object> initializationResult() {
        return initializationResult;
    }

    // ------------------------------------------------------------------ reading

    private void readLoop() {
        try {
            Iterator<Map<String, Object>> messages = transport.readMessages();
            while (!closed && messages.hasNext()) {
                Map<String, Object> message = messages.next();
                if (closed) {
                    break;
                }
                route(message);
            }
        } catch (RuntimeException e) {
            if (!closed) {
                fail(e);
            }
        } finally {
            terminate(); // before the (possibly slow) final flush, so pending requests fail now
            TranscriptMirror mirror = transcriptMirror;
            if (mirror != null) {
                mirror.flush();
            }
            lifecycle.onReaderFinished();
            queue.add(END);
        }
    }

    private void route(Map<String, Object> message) {
        String type = Json.string(message, "type");
        switch (type == null ? "" : type) {
            case "control_response" -> {
                completeControlResponse(Json.object(message, "response"));
                return;
            }
            case "control_request" -> {
                spawnControlRequestHandler(message);
                return;
            }
            case "control_cancel_request" -> {
                cancelInflightRequest(Json.string(message, "request_id"));
                return;
            }
            case "transcript_mirror" -> {
                TranscriptMirror mirror = transcriptMirror;
                if (mirror != null) {
                    mirror.enqueue(Json.string(message, "filePath"), Json.objectList(message, "entries"));
                }
                return;
            }
            default -> {
            }
        }
        boolean sessionStateFrame = "system".equals(type) && "session_state_changed".equals(message.get("subtype"));
        if ("system".equals(type)) {
            lifecycle.onSystemFrame(message);
            // Frames the CLI sent only because the transport asked for them; the caller did not opt in.
            if (sessionStateFrame && Boolean.TRUE.equals(message.get("sdk_host_only"))) {
                return;
            }
        }
        if ("result".equals(type)) {
            TranscriptMirror mirror = transcriptMirror;
            if (mirror != null) {
                mirror.flush(); // the store is up to date for this turn when the consumer sees the result
            }
            lifecycle.onResult();
            lastErrorResult = Json.isTrue(message, "is_error") ? message : null;
        } else if (!sessionStateFrame) {
            // The conversation moved on: a later ProcessException is a fresh crash.
            lastErrorResult = null;
            if (("assistant".equals(type) || "stream_event".equals(type)) && message.get("parent_tool_use_id") == null) {
                lifecycle.onMainThreadTurnActivity();
            }
        }
        queue.add(new Envelope.Data(message));
    }

    /**
     * Replaces the CLI's bare "exit code 1" after an error result with a {@link ResultException}
     * carrying that result, fails pending requests fast, and queues the error for the consumer.
     */
    private void fail(RuntimeException error) {
        RuntimeException pending = error;
        Map<String, Object> errorResult = lastErrorResult;
        if (error instanceof ProcessException processError && errorResult != null) {
            pending = new ResultException("Claude Code returned an error result: " + errorResultText(errorResult),
                    errorResult, processError.exitCode());
            pending.initCause(error);
        } else {
            // The error reaches the consumer; the stack trace only at DEBUG.
            LOG.log(Level.ERROR, "Fatal error in message reader: {0}", error.getMessage());
            LOG.log(Level.DEBUG, "Fatal error in message reader", error);
        }
        for (CompletableFuture<Map<String, Object>> future : pendingRequests.values()) {
            future.completeExceptionally(pending);
        }
        queue.add(new Envelope.Failure(pending));
    }

    /** Most informative text of an error result: errors[], then result, then subtype, then HTTP status. */
    static String errorResultText(Map<String, Object> message) {
        List<String> errors = ResultException.normalizeErrors(message.get("errors"));
        if (!errors.isEmpty()) {
            return String.join("; ", errors);
        }
        String result = Json.string(message, "result");
        if (result != null && !result.isBlank()) {
            return result.strip();
        }
        String subtype = Json.string(message, "subtype");
        if (subtype != null && !subtype.isEmpty() && !"success".equals(subtype)) {
            return subtype;
        }
        Object status = message.get("api_error_status");
        return status != null ? "API error (HTTP " + status + ")" : "unknown error";
    }

    /**
     * The non-control messages. {@code hasNext()} blocks; the iterator ends at end of stream and
     * rethrows a reader failure. Several iterators may be taken over time; they share one stream.
     */
    public Iterator<Map<String, Object>> receive() {
        return new Iterator<>() {
            private Map<String, Object> next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (done) {
                    return false;
                }
                Envelope envelope;
                try {
                    envelope = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ClaudeSdkException("Interrupted while waiting for messages", e);
                }
                switch (envelope) {
                    case Envelope.Data data -> next = data.message();
                    case Envelope.End end -> {
                        done = true;
                        queue.add(end); // later iterators end immediately too
                    }
                    case Envelope.Failure failure -> {
                        done = true;
                        throw failure.error();
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

    // ------------------------------------------------------------------ control requests from the CLI

    private void spawnControlRequestHandler(Map<String, Object> message) {
        if (closed) {
            return;
        }
        String requestId = Json.string(message, "request_id");
        Map<String, Object> request = Json.object(message, "request");
        ControlRequestHandler handler = request == null ? null : handlers.get(Json.string(request, "subtype"));
        if (handler != null) {
            handler.onReceived(request);
        }
        Thread thread = Thread.ofVirtual().name("claude-control-" + requestId).unstarted(
                () -> handleControlRequest(requestId, request));
        inflightRequests.put(requestId, thread);
        thread.start();
    }

    private void cancelInflightRequest(String requestId) {
        Thread thread = requestId == null ? null : inflightRequests.remove(requestId);
        if (thread != null) {
            thread.interrupt(); // the CLI abandoned the request; no response is written
        }
    }

    private void handleControlRequest(String requestId, Map<String, Object> request) {
        Map<String, Object> response;
        try {
            String subtype = Json.string(request, "subtype");
            ControlRequestHandler handler = handlers.get(subtype);
            if (handler == null) {
                throw new UnsupportedOperationException("Unsupported control request subtype: " + subtype);
            }
            Map<String, Object> payload = handler.handle(request);
            response = Json.obj("subtype", "success", "request_id", requestId,
                    "response", payload == null ? Map.of() : payload);
        } catch (InterruptedException e) {
            response = null;
        } catch (Exception e) {
            String error = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            response = Json.obj("subtype", "error", "request_id", requestId, "error", error);
        } finally {
            Thread.interrupted(); // clear a cancellation interrupt before writing
        }
        // Removed by cancelInflightRequest: the CLI no longer waits for an answer.
        if (inflightRequests.remove(requestId) == null || response == null || closed) {
            return;
        }
        try {
            transport.write(Json.write(Map.of("type", "control_response", "response", response)) + "\n");
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Could not send control response " + requestId, e);
        }
    }

    // ------------------------------------------------------------------ control requests to the CLI

    /** Sends a control request with the default 60 s timeout. */
    public Map<String, Object> sendControlRequest(Map<String, Object> request) {
        return sendControlRequest(request, 60_000);
    }

    /**
     * Sends a control request and waits for its response.
     *
     * @return the {@code response} payload of the CLI's success response
     * @throws ClaudeSdkException on an error response, a timeout or a closed connection
     */
    public Map<String, Object> sendControlRequest(Map<String, Object> request, long timeoutMillis) {
        String requestId = "req_" + requestCounter.incrementAndGet() + "_" + randomHex();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);
        try {
            if (terminated) { // re-checked after registering: terminate() may have run in between
                throw new CliConnectionException("Connection closed");
            }
            transport.write(Json.write(Map.of("type", "control_request", "request_id", requestId,
                    "request", request)) + "\n");
            Map<String, Object> response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            Map<String, Object> payload = Json.object(response, "response");
            return payload != null ? payload : Map.of();
        } catch (TimeoutException e) {
            throw new ClaudeSdkException("Control request timeout: " + request.get("subtype"), e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime
                    : new ClaudeSdkException(String.valueOf(e.getCause().getMessage()), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeSdkException("Interrupted while waiting for control response", e);
        } finally {
            pendingRequests.remove(requestId);
        }
    }

    private void completeControlResponse(Map<String, Object> response) {
        String requestId = Json.string(response, "request_id");
        CompletableFuture<Map<String, Object>> future = requestId == null ? null : pendingRequests.get(requestId);
        if (future == null) {
            return;
        }
        if ("error".equals(response.get("subtype"))) {
            String error = Json.string(response, "error");
            future.completeExceptionally(new ClaudeSdkException(error != null ? error : "Unknown error"));
        } else {
            future.complete(response);
        }
    }

    /** Fails every pending control request; a reader failure already completed them with its cause. */
    private void terminate() {
        terminated = true;
        CliConnectionException closedError = new CliConnectionException("Connection closed");
        pendingRequests.values().forEach(future -> future.completeExceptionally(closedError));
    }

    private static String randomHex() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public Map<String, Object> mcpStatus() {
        return sendControlRequest(Map.of("subtype", "mcp_status"));
    }

    public Map<String, Object> contextUsage() {
        return sendControlRequest(Map.of("subtype", "get_context_usage"));
    }

    public void interrupt() {
        sendControlRequest(Map.of("subtype", "interrupt"));
    }

    public void setPermissionMode(PermissionMode mode) {
        sendControlRequest(Map.of("subtype", "set_permission_mode", "mode", mode.wireValue()));
    }

    public void setModel(String model) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("subtype", "set_model");
        request.put("model", model); // null resets to the default model
        sendControlRequest(request);
    }

    public void rewindFiles(String userMessageId) {
        sendControlRequest(Map.of("subtype", "rewind_files", "user_message_id", userMessageId));
    }

    public void reconnectMcpServer(String serverName) {
        sendControlRequest(Map.of("subtype", "mcp_reconnect", "serverName", serverName));
    }

    public void toggleMcpServer(String serverName, boolean enabled) {
        sendControlRequest(Map.of("subtype", "mcp_toggle", "serverName", serverName, "enabled", enabled));
    }

    public void stopTask(String taskId) {
        sendControlRequest(Map.of("subtype", "stop_task", "task_id", taskId));
    }

    // ------------------------------------------------------------------ user input

    /** Writes one user message (stamped for {@code verbatimPrompts}). */
    public void writeUserMessage(Map<String, Object> message) {
        transport.write(Json.write(stamp(message)) + "\n");
    }

    private Map<String, Object> stamp(Map<String, Object> message) {
        if (!settings.verbatimPrompts()) {
            return message;
        }
        Map<String, Object> copy = new LinkedHashMap<>(message);
        copy.put("client_composed", true);
        return copy;
    }

    /** Writes a string prompt, then closes stdin once the run is over (on a background thread). */
    public void sendPromptAndEndInput(Map<String, Object> userMessage) {
        writeUserMessage(userMessage);
        spawnChild("claude-end-input", this::waitForRunEndAndEndInput);
    }

    /** Streams prompts on a background thread, then closes stdin once the last prompt's run is over. */
    public void streamInputInBackground(Iterable<Map<String, Object>> prompts) {
        spawnChild("claude-stream-input", () -> streamInput(prompts));
    }

    private void streamInput(Iterable<Map<String, Object>> prompts) {
        int written = 0;
        try {
            for (Map<String, Object> message : prompts) {
                if (closed) {
                    break;
                }
                lifecycle.onPromptWritten();
                writeUserMessage(message);
                written++;
            }
        } catch (RuntimeException e) {
            // Don't leave stdin open: the CLI would wait for input forever.
            LOG.log(Level.ERROR, "Prompt stream failed; closing stdin: " + e.getMessage(), e);
        }
        try {
            if (written > 0) {
                waitForRunEndAndEndInput();
            } else {
                lifecycle.markFinal(); // nothing was sent, so no result will arrive
                transport.endInput();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "Error closing input stream", e);
        }
    }

    /**
     * Keeps stdin open while the CLI may still need control responses (hooks, permission callback,
     * SDK MCP servers), then closes it.
     */
    private void waitForRunEndAndEndInput() {
        if (lifecycle.bidirectional()) {
            try {
                lifecycle.awaitRunEnd();
            } catch (InterruptedException e) {
                return; // closed
            }
        }
        lifecycle.markFinal();
        transport.endInput();
    }

    private void spawnChild(String name, Runnable task) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                task.run();
            } finally {
                childThreads.remove(Thread.currentThread());
            }
        });
        childThreads.add(thread);
        thread.start();
    }

    // ------------------------------------------------------------------ shutdown

    /** Stops all background work (incl. running in-process MCP calls) and the transport. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        lifecycle.close();
        terminate();
        TranscriptMirror mirror = transcriptMirror;
        if (mirror != null) {
            mirror.close();
        }
        childThreads.forEach(Thread::interrupt);
        inflightRequests.values().forEach(Thread::interrupt);
        try {
            transport.close();
        } finally {
            Thread reader = readerThread;
            if (reader != null) {
                try {
                    reader.join(CLOSE_JOIN_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            queue.add(END);
        }
    }
}
