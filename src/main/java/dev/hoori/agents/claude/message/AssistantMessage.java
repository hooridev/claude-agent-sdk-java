package dev.hoori.agents.claude.message;

import java.util.List;
import java.util.Map;

/**
 * An assistant message with content blocks.
 *
 * @param error             one of {@code authentication_failed}, {@code billing_error}, {@code rate_limit},
 *                          {@code invalid_request}, {@code server_error}, {@code unknown}; or {@code null}
 * @param userMessageUuid   uuid of the user message this turn is answering, if reported
 * @param userMessageUuids  uuid of every user message this turn has taken in so far, if reported
 */
public record AssistantMessage(
        List<ContentBlock> content,
        String model,
        String parentToolUseId,
        String error,
        Map<String, Object> usage,
        String messageId,
        String stopReason,
        String sessionId,
        String uuid,
        String userMessageUuid,
        List<String> userMessageUuids) implements Message {

    public AssistantMessage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Concatenates the text of all {@link TextBlock}s. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextBlock(String t)) {
                sb.append(t);
            }
        }
        return sb.toString();
    }
}
