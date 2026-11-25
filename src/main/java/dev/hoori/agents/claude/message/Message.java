package dev.hoori.agents.claude.message;

/**
 * A message received from Claude Code.
 *
 * <p>Use pattern matching to branch on the concrete type:
 *
 * <pre>{@code
 * switch (message) {
 *     case AssistantMessage a -> ...;
 *     case ResultMessage r -> ...;
 *     default -> { }
 * }
 * }</pre>
 */
public sealed interface Message
        permits UserMessage, AssistantMessage, SystemMessage, ResultMessage, StreamEvent, RateLimitEvent,
        ConversationResetMessage {
}
