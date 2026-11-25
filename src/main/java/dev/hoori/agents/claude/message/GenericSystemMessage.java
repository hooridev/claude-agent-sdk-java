package dev.hoori.agents.claude.message;

import java.util.Map;

/** A system message whose subtype has no dedicated type (e.g. {@code init}). */
public record GenericSystemMessage(String subtype, Map<String, Object> data) implements SystemMessage {
}
