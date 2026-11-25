package dev.hoori.agents.claude.permission;

import java.util.Map;

/**
 * Callback deciding whether a tool call may run (Python: {@code can_use_tool}).
 *
 * <p>Invoked on a virtual thread for each permission request the CLI sends. Setting it routes
 * permission prompts over the control protocol ({@code --permission-prompt-tool stdio}).
 * Tools allowed outright by {@code allowedTools} or by {@code bypassPermissions} never reach it.
 */
@FunctionalInterface
public interface CanUseTool {

    /**
     * @param toolName the tool, e.g. {@code Bash}
     * @param input    the tool input
     * @param context  extra information about the request
     * @return {@link PermissionResult#allow()} or {@link PermissionResult#deny(String)}
     * @throws Exception any exception is reported to the CLI as an error response
     */
    PermissionResult check(String toolName, Map<String, Object> input, ToolPermissionContext context) throws Exception;
}
