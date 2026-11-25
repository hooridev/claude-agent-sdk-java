package dev.hoori.agents.claude.session;

/**
 * A user or assistant message of a stored session transcript.
 *
 * @param type            {@code user} or {@code assistant}
 * @param message         the raw Anthropic API message (role, content, ...)
 * @param parentToolUseId for subagent messages: id of the Agent tool_use that spawned the subagent
 * @param parentAgentId   for subagent messages: agent id of the spawning subagent, {@code null} if spawned
 *                        by the main session
 */
public record SessionMessage(
        String type,
        String uuid,
        String sessionId,
        Object message,
        String parentToolUseId,
        String parentAgentId) {
}
