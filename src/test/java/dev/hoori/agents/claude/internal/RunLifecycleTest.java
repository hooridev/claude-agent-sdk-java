package dev.hoori.agents.claude.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class RunLifecycleTest {

    private static boolean endsWithin(RunLifecycle lifecycle, long millis) {
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
            try {
                lifecycle.awaitRunEnd();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            waiter.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            waiter.cancel(true);
            return false;
        }
    }

    private static Map<String, Object> state(String state) {
        return Map.of("subtype", "session_state_changed", "state", state);
    }

    @Test
    void resultEndsTheRunWithoutSessionState() {
        RunLifecycle lifecycle = new RunLifecycle(true, 0);
        lifecycle.onResult();
        assertTrue(endsWithin(lifecycle, 500));
    }

    @Test
    void runningStateWaitsForIdle() {
        RunLifecycle lifecycle = new RunLifecycle(true, 0);
        lifecycle.onSystemFrame(state("running"));
        lifecycle.onResult();
        assertFalse(endsWithin(lifecycle, 200));
        lifecycle.onSystemFrame(state("idle"));
        assertTrue(endsWithin(lifecycle, 500));
    }

    @Test
    void inflightAgentTaskDefersTheEnd() {
        RunLifecycle lifecycle = new RunLifecycle(true, 0);
        lifecycle.onSystemFrame(Map.of("subtype", "task_started", "task_id", "t1", "task_type", "local_agent"));
        lifecycle.onSystemFrame(Map.of("subtype", "task_started", "task_id", "b1", "task_type", "local_bash"));
        lifecycle.onResult();
        assertFalse(endsWithin(lifecycle, 200), "an agent task may wake the session for another turn");
        lifecycle.onSystemFrame(Map.of("subtype", "task_updated", "task_id", "t1", "patch", Map.of("status", "completed")));
        lifecycle.onResult();
        assertTrue(endsWithin(lifecycle, 500), "background shells are not tracked");
    }

    @Test
    void taskStartedWithoutTypeIsIgnored() {
        RunLifecycle lifecycle = new RunLifecycle(true, 0);
        lifecycle.onSystemFrame(Map.of("subtype", "task_started", "task_id", "t1"));
        lifecycle.onResult();
        assertTrue(endsWithin(lifecycle, 500));
    }

    @Test
    void ceilingEndsARunThatStaysRunning() {
        RunLifecycle lifecycle = new RunLifecycle(true, 100);
        lifecycle.onSystemFrame(state("running"));
        lifecycle.onResult();
        assertTrue(endsWithin(lifecycle, 2000));
    }

    @Test
    void requiresActionStopsTheCeiling() {
        RunLifecycle lifecycle = new RunLifecycle(true, 100);
        lifecycle.onSystemFrame(state("running"));
        lifecycle.onResult();
        lifecycle.onSystemFrame(state("requires_action"));
        assertFalse(endsWithin(lifecycle, 400));
    }

    @Test
    void ceilingIsReadFromOptionsEnvFirst() {
        assertEquals(5, RunLifecycle.ceilingFromEnv(Map.of(RunLifecycle.RUN_END_CEILING_ENV, "5")));
        assertEquals(RunLifecycle.DEFAULT_RUN_END_CEILING_MS,
                RunLifecycle.ceilingFromEnv(Map.of(RunLifecycle.RUN_END_CEILING_ENV, "1e6")));
        assertEquals(RunLifecycle.DEFAULT_RUN_END_CEILING_MS,
                RunLifecycle.ceilingFromEnv(Map.of(RunLifecycle.RUN_END_CEILING_ENV, "-1")));
    }
}
