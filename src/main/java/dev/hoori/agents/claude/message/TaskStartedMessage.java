package dev.hoori.agents.claude.message;

import java.util.Map;

/** A background task (e.g. a subagent) was started. */
public record TaskStartedMessage(
        String subtype,
        Map<String, Object> data,
        String taskId,
        String description,
        String uuid,
        String sessionId,
        String toolUseId,
        String taskType) implements SystemMessage {
}
