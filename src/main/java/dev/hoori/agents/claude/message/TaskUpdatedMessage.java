package dev.hoori.agents.claude.message;

import java.util.Map;
import java.util.Set;

/**
 * A task state patch. {@code status} is one of {@code pending}, {@code running}, {@code paused},
 * {@code completed}, {@code failed}, {@code killed}, or {@code null} if the patch carries none.
 */
public record TaskUpdatedMessage(
        String subtype,
        Map<String, Object> data,
        String taskId,
        Map<String, Object> patch,
        String status,
        String sessionId,
        String uuid) implements SystemMessage {

    /** Statuses (of both task message vocabularies) that mean the task has finished. */
    public static final Set<String> TERMINAL_TASK_STATUSES = Set.of("completed", "failed", "stopped", "killed");

    /** Whether {@link #status()} is terminal. */
    public boolean isTerminal() {
        return status != null && TERMINAL_TASK_STATUSES.contains(status);
    }
}
