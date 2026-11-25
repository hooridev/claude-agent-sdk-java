package dev.hoori.agents.claude.hook;

import java.util.LinkedHashMap;
import java.util.Map;

/** Factories for the {@code hookSpecificOutput} object of a {@link HookOutput}. */
public final class HookSpecificOutput {

    private HookSpecificOutput() {
    }

    /** A builder for any event, e.g. {@code forEvent("SessionStart").put("additionalContext", "...")}. */
    public static Builder forEvent(String hookEventName) {
        return new Builder(hookEventName);
    }

    /**
     * PreToolUse decision.
     *
     * @param permissionDecision {@code allow}, {@code deny}, {@code ask} or {@code defer}
     * @param reason             shown to the model / user, or {@code null}
     */
    public static Map<String, Object> preToolUse(String permissionDecision, String reason) {
        return forEvent("PreToolUse").put("permissionDecision", permissionDecision)
                .put("permissionDecisionReason", reason).build();
    }

    /** Adds context for Claude after a tool ran. */
    public static Map<String, Object> postToolUse(String additionalContext) {
        return forEvent("PostToolUse").put("additionalContext", additionalContext).build();
    }

    /** Adds context to a submitted prompt. */
    public static Map<String, Object> userPromptSubmit(String additionalContext) {
        return forEvent("UserPromptSubmit").put("additionalContext", additionalContext).build();
    }

    /** Builder for an event-specific output object. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder(String hookEventName) {
            fields.put("hookEventName", hookEventName);
        }

        /** Adds a field; {@code null} values are skipped. */
        public Builder put(String key, Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Map<String, Object> build() {
            return Map.copyOf(fields);
        }
    }
}
