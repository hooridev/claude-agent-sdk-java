package dev.hoori.agents.claude.message;

import java.util.Map;

/** Progress of a background task; {@code usage} holds {@code total_tokens}, {@code tool_uses}, {@code duration_ms}. */
public record TaskProgressMessage(
        String subtype,
        Map<String, Object> data,
        String taskId,
        String description,
        Map<String, Object> usage,
        String uuid,
        String sessionId,
        String toolUseId,
        String lastToolName) implements SystemMessage {
}
