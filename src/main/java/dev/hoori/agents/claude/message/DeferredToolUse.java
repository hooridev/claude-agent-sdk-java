package dev.hoori.agents.claude.message;

import java.util.Map;

/** A tool call a PreToolUse hook deferred with {@code permissionDecision: "defer"}. */
public record DeferredToolUse(String id, String name, Map<String, Object> input) {
}
