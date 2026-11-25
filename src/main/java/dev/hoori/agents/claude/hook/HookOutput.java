package dev.hoori.agents.claude.hook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The output of a hook callback, built fluently:
 *
 * <pre>{@code
 * return HookOutput.builder()
 *         .hookSpecificOutput(HookSpecificOutput.preToolUse("deny", "Not allowed"))
 *         .build();
 * }</pre>
 *
 * <p>Field names are the CLI's ({@code continue}, {@code async}); no renaming as in Python
 * ({@code continue_}) is needed in Java.
 */
public record HookOutput(Map<String, Object> fields) {

    public HookOutput {
        fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** "No opinion". */
    public static HookOutput empty() {
        return new HookOutput(Map.of());
    }

    /** Wraps a raw output object. */
    public static HookOutput of(Map<String, Object> fields) {
        return new HookOutput(fields);
    }

    /** Defers the hook ({@code {"async": true}}). */
    public static HookOutput async(Integer asyncTimeoutMs) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("async", true);
        if (asyncTimeoutMs != null) {
            fields.put("asyncTimeout", asyncTimeoutMs);
        }
        return new HookOutput(fields);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for synchronous hook output. */
    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        /** Whether Claude should proceed after the hook (default {@code true}). */
        public Builder continueExecution(boolean value) {
            fields.put("continue", value);
            return this;
        }

        /** Hide stdout from transcript mode. */
        public Builder suppressOutput(boolean value) {
            fields.put("suppressOutput", value);
            return this;
        }

        /** Message shown when {@code continue} is false. */
        public Builder stopReason(String value) {
            fields.put("stopReason", value);
            return this;
        }

        /** Set to {@code "block"} to block. */
        public Builder decision(String value) {
            fields.put("decision", value);
            return this;
        }

        /** Warning shown to the user. */
        public Builder systemMessage(String value) {
            fields.put("systemMessage", value);
            return this;
        }

        /** Feedback for Claude about the decision. */
        public Builder reason(String value) {
            fields.put("reason", value);
            return this;
        }

        /** Event-specific output, see {@link HookSpecificOutput}. */
        public Builder hookSpecificOutput(Map<String, Object> value) {
            fields.put("hookSpecificOutput", value);
            return this;
        }

        /** Any other field. */
        public Builder put(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public HookOutput build() {
            return new HookOutput(fields);
        }
    }
}
