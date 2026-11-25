package dev.hoori.agents.claude.hook;

import java.util.List;

/**
 * Binds hook callbacks to a matcher.
 *
 * @param matcher   e.g. a tool name like {@code Bash} or {@code Write|MultiEdit|Edit}; {@code null} matches all
 * @param hooks     the callbacks
 * @param timeout   timeout in seconds for all hooks in this matcher (CLI default: 60)
 * @param onFailure what happens when a callback fails, or {@code null} for the CLI default
 */
public record HookMatcher(String matcher, List<HookCallback> hooks, Double timeout, HookFailureMode onFailure) {

    public HookMatcher {
        hooks = hooks == null ? List.of() : List.copyOf(hooks);
    }

    /** Matches everything. */
    public static HookMatcher of(HookCallback... hooks) {
        return new HookMatcher(null, List.of(hooks), null, null);
    }

    /** Matches the given pattern. */
    public static HookMatcher of(String matcher, HookCallback... hooks) {
        return new HookMatcher(matcher, List.of(hooks), null, null);
    }

    public HookMatcher withTimeout(double seconds) {
        return new HookMatcher(matcher, hooks, seconds, onFailure);
    }

    public HookMatcher withOnFailure(HookFailureMode mode) {
        return new HookMatcher(matcher, hooks, timeout, mode);
    }
}
