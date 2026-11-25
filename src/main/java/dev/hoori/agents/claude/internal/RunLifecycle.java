package dev.hoori.agents.claude.internal;

import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import dev.hoori.agents.claude.message.TaskUpdatedMessage;

/**
 * Decides when a run is over, i.e. when stdin may be closed (Python: the run-end logic of
 * {@code Query}, issues #1088/#1190 there).
 *
 * <p>A {@code result} frame ends one turn, not necessarily the run: background agents may still
 * need hook, permission and SDK MCP control responses over stdin. A CLI that reports
 * {@code session_state_changed} stays {@code running} while such work is owed and reports
 * {@code idle} once no further turn is owed; for older CLIs a ledger of started-but-unfinished
 * agent tasks is the fallback. A ceiling ({@code CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS}) bounds the
 * wait between turns.
 */
final class RunLifecycle {

    private static final System.Logger LOG = System.getLogger(RunLifecycle.class.getName());

    /** Task types whose completion runs a follow-up turn (see the Python SDK for why only these). */
    static final Set<String> DEFERRING_TASK_TYPES = Set.of("local_agent", "local_workflow");
    static final String RUN_END_CEILING_ENV = "CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS";
    static final long DEFAULT_RUN_END_CEILING_MS = 600_000;
    private static final long MAX_RUN_END_CEILING_MS = Integer.MAX_VALUE;

    private final boolean bidirectional;
    private final long ceilingMs;

    /** Completed when the run is over; replaced (not reset) when work reopens it, like Python's Event swap. */
    private CompletableFuture<Void> runEnded = new CompletableFuture<>();
    private final Set<String> inflightTasks = new HashSet<>();
    private boolean resultReceived;
    private boolean turnInProgress;
    private boolean runFinal;
    private boolean closed;
    private String sessionState;
    private Thread ceilingThread;
    private long ceilingGeneration;

    /**
     * @param bidirectional whether the CLI may send control requests that need a reply (hooks,
     *                      permission callback or SDK MCP servers)
     */
    RunLifecycle(boolean bidirectional, long ceilingMs) {
        this.bidirectional = bidirectional;
        this.ceilingMs = ceilingMs;
    }

    /** Reads the ceiling as the CLI will see it: {@code optionsEnv} overrides the inherited environment. */
    static long ceilingFromEnv(Map<String, String> optionsEnv) {
        String raw = optionsEnv.containsKey(RUN_END_CEILING_ENV) ? optionsEnv.get(RUN_END_CEILING_ENV)
                : System.getenv(RUN_END_CEILING_ENV);
        if (raw == null) {
            return DEFAULT_RUN_END_CEILING_MS;
        }
        try {
            long value = Long.parseLong(raw.strip());
            return value >= 0 ? value : DEFAULT_RUN_END_CEILING_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_RUN_END_CEILING_MS;
        }
    }

    boolean bidirectional() {
        return bidirectional;
    }

    // ------------------------------------------------------------------ events from the reader

    /** A {@code system} frame: tracks task lifecycle and session state. */
    synchronized void onSystemFrame(Map<String, Object> message) {
        boolean hadTasksInFlight = !inflightTasks.isEmpty();
        trackTaskLifecycle(message);
        if (hadTasksInFlight && inflightTasks.isEmpty()) {
            rearmCeilingBetweenTurns();
        }
        if ("session_state_changed".equals(message.get("subtype"))) {
            onSessionState(Json.string(message, "state"));
        }
    }

    /** A {@code result} frame. */
    synchronized void onResult() {
        resultReceived = true;
        turnInProgress = false;
        if (sessionState == null || "idle".equals(sessionState) || !bidirectional) {
            maybeEndRun();
        } else if (!"requires_action".equals(sessionState)) {
            armCeiling();
        }
    }

    /** Main-thread assistant/stream frames: a turn is under way. */
    synchronized void onMainThreadTurnActivity() {
        turnInProgress = true;
        reopenRun();
        clearCeiling();
    }

    /** A prompt is about to be written; it owes a run of its own. */
    synchronized void onPromptWritten() {
        reopenRun();
        resultReceived = false;
        clearCeiling();
    }

    /** stdin is about to be closed: the run stays ended from now on. */
    synchronized void markFinal() {
        runFinal = true;
        clearCeiling();
    }

    /** The reader is gone (EOF or error): wake every waiter. */
    synchronized void onReaderFinished() {
        runFinal = true;
        endRun();
    }

    synchronized void close() {
        closed = true;
        clearCeiling();
    }

    /** Blocks until the run is over. */
    void awaitRunEnd() throws InterruptedException {
        CompletableFuture<Void> current;
        synchronized (this) {
            current = runEnded;
        }
        try {
            current.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ internals (hold the monitor)

    private void trackTaskLifecycle(Map<String, Object> message) {
        String taskId = Json.string(message, "task_id");
        if (taskId == null || taskId.isEmpty()) {
            return;
        }
        switch (String.valueOf(message.get("subtype"))) {
            case "task_started" -> {
                String taskType = Json.string(message, "task_type");
                if (taskType != null && DEFERRING_TASK_TYPES.contains(taskType)) {
                    inflightTasks.add(taskId);
                }
            }
            case "task_notification" -> inflightTasks.remove(taskId);
            case "task_updated" -> {
                String status = Json.string(Json.object(message, "patch"), "status");
                if (status != null && TaskUpdatedMessage.TERMINAL_TASK_STATUSES.contains(status)) {
                    inflightTasks.remove(taskId);
                }
            }
            default -> {
            }
        }
    }

    private void onSessionState(String state) {
        sessionState = state;
        if ("idle".equals(state)) {
            if (resultReceived) {
                maybeEndRun();
            }
            return;
        }
        // Work the CLI took up after the run ended (a background task woke it) reopens the run.
        reopenRun();
        if ("requires_action".equals(state)) {
            clearCeiling();
        } else {
            rearmCeilingBetweenTurns();
        }
    }

    private void maybeEndRun() {
        if (!inflightTasks.isEmpty()) {
            LOG.log(Level.DEBUG, "Turn ended with {0} task(s) in flight; keeping stdin open", inflightTasks.size());
            return;
        }
        endRun();
    }

    private void endRun() {
        clearCeiling();
        runEnded.complete(null);
    }

    private void reopenRun() {
        if (runEnded.isDone() && !runFinal) {
            runEnded = new CompletableFuture<>();
        }
    }

    private void rearmCeilingBetweenTurns() {
        if (resultReceived && sessionState != null && !"idle".equals(sessionState)
                && !"requires_action".equals(sessionState)) {
            armCeiling();
        }
    }

    private void armCeiling() {
        clearCeiling();
        if (ceilingMs <= 0 || runEnded.isDone() || runFinal || closed || turnInProgress || !bidirectional) {
            return;
        }
        long generation = ceilingGeneration;
        ceilingThread = Thread.ofVirtual().name("claude-run-ceiling").start(() -> endRunAtCeiling(generation));
    }

    private void endRunAtCeiling(long generation) {
        try {
            Thread.sleep(Math.min(ceilingMs, MAX_RUN_END_CEILING_MS));
        } catch (InterruptedException e) {
            return;
        }
        synchronized (this) {
            if (generation != ceilingGeneration) {
                return; // cleared or re-armed meanwhile
            }
            ceilingThread = null;
            if (!inflightTasks.isEmpty()) {
                return; // a tracked agent may still need stdin; the ceiling restarts once it settles
            }
            LOG.log(Level.DEBUG, "No ''idle'' {0}ms after the last result; ending the run", ceilingMs);
            endRun();
        }
    }

    private void clearCeiling() {
        ceilingGeneration++;
        Thread thread = ceilingThread;
        ceilingThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
