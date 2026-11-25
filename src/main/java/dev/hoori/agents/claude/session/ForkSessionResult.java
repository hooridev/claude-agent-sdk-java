package dev.hoori.agents.claude.session;

/**
 * Result of a fork.
 *
 * @param sessionId UUID of the new, forked session
 */
public record ForkSessionResult(String sessionId) {
}
