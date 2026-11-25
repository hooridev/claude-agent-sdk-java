package dev.hoori.agents.claude.message;

/** The conversation was reset (e.g. {@code /clear}) within a streaming connection. */
public record ConversationResetMessage(String newConversationId, String uuid, String sessionId) implements Message {
}
