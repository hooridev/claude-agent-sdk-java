package dev.hoori.agents.claude.message;

import java.util.Map;

/** A tool call requested by the model. */
public record ToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {
}
