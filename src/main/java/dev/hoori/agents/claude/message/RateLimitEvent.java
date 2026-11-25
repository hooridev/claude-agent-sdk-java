package dev.hoori.agents.claude.message;

/** Emitted whenever the rate limit status changes. */
public record RateLimitEvent(RateLimitInfo rateLimitInfo, String uuid, String sessionId) implements Message {
}
