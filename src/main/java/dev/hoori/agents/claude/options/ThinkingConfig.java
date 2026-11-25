package dev.hoori.agents.claude.options;

/** Extended thinking configuration ({@code --thinking}, {@code --max-thinking-tokens}). */
public sealed interface ThinkingConfig {

    static ThinkingConfig adaptive() {
        return new Adaptive(null);
    }

    static ThinkingConfig enabled(int budgetTokens) {
        return new Enabled(budgetTokens, null);
    }

    static ThinkingConfig disabled() {
        return new Disabled();
    }

    /**
     * Adaptive thinking.
     *
     * @param display {@code summarized}, {@code omitted} or {@code null}
     */
    record Adaptive(String display) implements ThinkingConfig {
    }

    /** Thinking with a fixed token budget; {@code display} as on {@link Adaptive}. */
    record Enabled(int budgetTokens, String display) implements ThinkingConfig {
    }

    /** No extended thinking. */
    record Disabled() implements ThinkingConfig {
    }
}
