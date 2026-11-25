package dev.hoori.agents.claude.message;

import java.util.Map;

/** Result block of a server-side tool call; {@code content} is passed through from the API. */
public record ServerToolResultBlock(String toolUseId, Map<String, Object> content) implements ContentBlock {
}
