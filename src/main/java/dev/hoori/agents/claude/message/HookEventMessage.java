package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * Hook lifecycle event, emitted when {@code includeHookEvents} is enabled.
 * {@code subtype} is {@code hook_started} or {@code hook_response}.
 */
public record HookEventMessage(
        String subtype,
        Map<String, Object> data,
        String hookEventName,
        String sessionId,
        String uuid) implements SystemMessage {
}
