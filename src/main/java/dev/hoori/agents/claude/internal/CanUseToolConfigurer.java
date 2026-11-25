package dev.hoori.agents.claude.internal;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.options.Skills;

/**
 * Validates {@code canUseTool} and routes permission prompts over stdio
 * (Python: {@code _configure_can_use_tool}). Shared by {@code query()} and the client.
 */
public final class CanUseToolConfigurer {

    private static final System.Logger LOG = System.getLogger(CanUseToolConfigurer.class.getName());
    private static final Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private CanUseToolConfigurer() {
    }

    /**
     * @return {@code options} unchanged without callback, else a copy with
     *         {@code permissionPromptToolName = "stdio"}
     * @throws IllegalArgumentException if combined with {@code permissionPromptToolName}
     */
    public static ClaudeAgentOptions configure(ClaudeAgentOptions options) {
        if (options.canUseTool() == null) {
            return options;
        }
        if (options.permissionPromptToolName() != null) {
            throw new IllegalArgumentException("canUseTool callback cannot be used with permissionPromptToolName. "
                    + "Please use one or the other.");
        }
        String warning = shadowedWarning(options);
        if (warning != null && WARNED.add(warning)) { // once per process, like Python's warning filter
            LOG.log(Level.WARNING, warning);
        }
        return options.toBuilder().permissionPromptToolName("stdio").build();
    }

    /** The warning for tool calls that are auto-approved before {@code canUseTool} runs, or {@code null}. */
    static String shadowedWarning(ClaudeAgentOptions options) {
        if (options.permissionMode() == PermissionMode.BYPASS_PERMISSIONS) {
            return "canUseTool will not be invoked: permission mode bypassPermissions auto-approves every tool call "
                    + "(except explicit deny rules) before the callback is consulted. To gate every tool call, use "
                    + "a PreToolUse hook instead.";
        }
        List<String> allowed = new ArrayList<>(options.allowedTools());
        if (options.skills() instanceof Skills.All && !allowed.contains("Skill")) {
            allowed.add("Skill");
        }
        Set<String> shadowed = new LinkedHashSet<>();
        for (String entry : allowed) {
            String tool = wholeToolAllowed(entry);
            if (tool != null) {
                shadowed.add(tool);
            }
        }
        if (shadowed.isEmpty()) {
            return null;
        }
        return "canUseTool will not be invoked for: " + String.join(", ", shadowed) + ". An allowedTools entry "
                + "that allows a whole tool auto-approves it before the callback is consulted. To gate every tool "
                + "call, use a PreToolUse hook; or narrow the entry so calls fall through to canUseTool.";
    }

    /** The tool an allowedTools entry allows outright ({@code Read}, {@code Read()}, {@code Read(*)}), else null. */
    static String wholeToolAllowed(String entry) {
        if (entry.isBlank()) {
            return null;
        }
        int open = entry.indexOf('(');
        if (open == -1) {
            return entry;
        }
        if (open == 0 || !entry.endsWith(")")) {
            return null;
        }
        String specifier = entry.substring(open + 1, entry.length() - 1);
        return specifier.isEmpty() || specifier.equals("*") ? entry.substring(0, open) : null;
    }
}
