package dev.hoori.agents.claude.message;

import java.util.List;
import java.util.Map;

/** A raw Anthropic API stream event, emitted when {@code includePartialMessages} is enabled. */
public record StreamEvent(
        String uuid,
        String sessionId,
        Map<String, Object> event,
        String parentToolUseId,
        String userMessageUuid,
        List<String> userMessageUuids) implements Message {
}
