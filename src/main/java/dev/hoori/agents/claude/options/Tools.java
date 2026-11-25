package dev.hoori.agents.claude.options;

import java.util.List;

/** The base set of built-in tools ({@code --tools}). */
public sealed interface Tools {

    /** Exactly these built-in tools; an empty list disables all built-in tools. */
    static Tools of(String... names) {
        return new Named(List.of(names));
    }

    /** Exactly these built-in tools; an empty list disables all built-in tools. */
    static Tools of(List<String> names) {
        return new Named(names);
    }

    /** All default Claude Code tools. */
    static Tools claudeCode() {
        return new Preset();
    }

    /** An explicit list of tool names. */
    record Named(List<String> names) implements Tools {
        public Named {
            names = List.copyOf(names);
        }
    }

    /** The {@code claude_code} preset. */
    record Preset() implements Tools {
    }
}
