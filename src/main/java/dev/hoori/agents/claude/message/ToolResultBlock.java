package dev.hoori.agents.claude.message;

import java.util.List;
import java.util.Map;

/**
 * The result of a tool call.
 *
 * @param toolUseId id of the {@link ToolUseBlock} this answers
 * @param content   a {@code String}, a {@code List<Map<String, Object>>} of content items, or {@code null}
 * @param isError   whether the tool reported an error, or {@code null} if unspecified
 */
public record ToolResultBlock(String toolUseId, Object content, Boolean isError) implements ContentBlock {

    /** Returns {@link #content()} if it is plain text, else {@code null}. */
    public String contentText() {
        return content instanceof String s ? s : null;
    }

    /** Returns {@link #content()} if it is a list of content items, else {@code null}. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> contentItems() {
        return content instanceof List<?> l ? (List<Map<String, Object>>) l : null;
    }
}
