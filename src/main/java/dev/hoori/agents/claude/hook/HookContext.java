package dev.hoori.agents.claude.hook;

/**
 * Context passed to hook callbacks. Reserved for future abort-signal support; a callback can
 * check {@link Thread#isInterrupted()} to notice that the CLI cancelled the request.
 */
public record HookContext() {
}
