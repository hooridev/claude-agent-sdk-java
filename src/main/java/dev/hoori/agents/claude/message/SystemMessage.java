package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * A {@code system} message. Known subtypes are parsed into dedicated records; anything else
 * becomes a {@link GenericSystemMessage}. For subtype {@code init}, {@link #data()} is the
 * session metadata frame (tools, MCP servers, model, ...).
 */
public sealed interface SystemMessage extends Message
        permits GenericSystemMessage, TaskStartedMessage, TaskProgressMessage, TaskNotificationMessage,
        TaskUpdatedMessage, MirrorErrorMessage, HookEventMessage {

    /** The system message subtype, e.g. {@code init}. */
    String subtype();

    /** The full raw message as emitted by the CLI. */
    Map<String, Object> data();
}
