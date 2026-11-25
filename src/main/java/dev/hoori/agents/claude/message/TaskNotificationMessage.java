package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * A background task finished. {@code status} is {@code completed}, {@code failed} or {@code stopped}.
 * Not every terminal task emits this; see {@link TaskUpdatedMessage}.
 */
public record TaskNotificationMessage(
        String subtype,
        Map<String, Object> data,
        String taskId,
        String status,
        String outputFile,
        String summary,
        String uuid,
        String sessionId,
        String toolUseId,
        Map<String, Object> usage) implements SystemMessage {
}
