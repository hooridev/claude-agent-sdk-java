package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * A tool the API executes server-side on the model's behalf (e.g. {@code web_search},
 * {@code web_fetch}, {@code advisor}). Branch on {@link #name()} to know which one.
 */
public record ServerToolUseBlock(String id, String name, Map<String, Object> input) implements ContentBlock {
}
