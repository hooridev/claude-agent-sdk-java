package dev.hoori.agents.claude.permission;

import java.util.List;

/**
 * Context of a permission request.
 *
 * @param suggestions    permission updates the CLI suggests (e.g. "always allow")
 * @param toolUseId      id of this tool call
 * @param agentId        the sub-agent's id when running within one
 * @param blockedPath    the path that triggered the request, if applicable
 * @param decisionReason why the request was triggered (e.g. a PreToolUse hook's "ask" reason)
 * @param title          full prompt sentence, e.g. "Claude wants to read foo.txt"
 * @param displayName    short noun phrase, e.g. "Read file"
 * @param description    human-readable subtitle
 */
public record ToolPermissionContext(
        List<PermissionUpdate> suggestions,
        String toolUseId,
        String agentId,
        String blockedPath,
        String decisionReason,
        String title,
        String displayName,
        String description) {

    public ToolPermissionContext {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }
}
