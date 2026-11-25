package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * Synthesized by the SDK (never by the CLI) when a {@code SessionStore} append failed.
 * Non-fatal: the local transcript is still durable.
 */
public record MirrorErrorMessage(
        String subtype,
        Map<String, Object> data,
        Map<String, Object> key,
        String error) implements SystemMessage {
}
