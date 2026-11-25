package dev.hoori.agents.claude.options;

/**
 * System prompt configuration. When no system prompt is configured the SDK passes an empty
 * one (like the Python SDK); use {@link #claudeCode()} to get Claude Code's default prompt.
 */
public sealed interface SystemPrompt {

    /** A plain custom system prompt ({@code --system-prompt}). */
    static SystemPrompt text(String prompt) {
        return new Custom(prompt, null);
    }

    /** Claude Code's own system prompt. */
    static Preset claudeCode() {
        return new Preset(null, null, null);
    }

    /** Claude Code's system prompt with extra text appended ({@code --append-system-prompt}). */
    static Preset claudeCode(String append) {
        return new Preset(append, null, null);
    }

    /** Reads the system prompt from a file ({@code --system-prompt-file}). */
    static SystemPrompt file(String path) {
        return new File(path);
    }

    /**
     * The {@code claude_code} preset.
     *
     * @param append                  text appended to the preset, or {@code null}
     * @param excludeDynamicSections  strip per-user dynamic sections so the prompt stays cacheable across users
     * @param snapshot                whether the session keeps the system prompt it recorded on its first request
     */
    record Preset(String append, Boolean excludeDynamicSections, Boolean snapshot) implements SystemPrompt {

        public Preset withExcludeDynamicSections(boolean value) {
            return new Preset(append, value, snapshot);
        }

        public Preset withSnapshot(boolean value) {
            return new Preset(append, excludeDynamicSections, value);
        }
    }

    /** A custom prompt; {@code snapshot} works as on {@link Preset}. */
    record Custom(String prompt, Boolean snapshot) implements SystemPrompt {
    }

    /** A system prompt file. */
    record File(String path) implements SystemPrompt {
    }
}
