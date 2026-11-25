package dev.hoori.agents.claude.message;

import java.util.List;
import java.util.Map;

/**
 * A user message (prompts, tool results, injected turns).
 *
 * @param text             the content if the CLI sent it as plain text, else {@code null}
 * @param content          the content blocks; empty when {@code text} is set
 * @param uuid             message id, if any
 * @param parentToolUseId  set when the message belongs to a subagent's tool call
 * @param toolUseResult    structured tool result, if any
 * @param origin           provenance of the message (see the Python SDK's {@code MessageOrigin}); {@code null}
 *                         when the CLI did not attribute it. Passed through as-is; only {@code kind} is always set.
 */
public record UserMessage(
        String text,
        List<ContentBlock> content,
        String uuid,
        String parentToolUseId,
        Map<String, Object> toolUseResult,
        Map<String, Object> origin) implements Message {

    public UserMessage {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /** Whether the message content was a plain string. */
    public boolean isText() {
        return text != null;
    }
}
