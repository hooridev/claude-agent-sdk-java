package dev.hoori.agents.claude.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookMatcher;
import dev.hoori.agents.claude.mcp.McpServer;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.options.Skills;
import dev.hoori.agents.claude.options.SystemPrompt;
import dev.hoori.agents.claude.permission.CanUseTool;

/**
 * Everything the {@link ControlProtocol} needs from the options, derived once so {@code query()}
 * and the client share the logic (the Python SDK duplicates it in both entry points).
 */
public record ProtocolSettings(
        CanUseTool canUseTool,
        Map<HookEvent, List<HookMatcher>> hooks,
        Map<String, McpServer> sdkMcpServers,
        long initializeTimeoutMillis,
        Map<String, Object> agents,
        Boolean excludeDynamicSections,
        Boolean systemPromptSnapshot,
        List<String> skills,
        boolean forwardSubagentText,
        boolean verbatimPrompts,
        long runEndCeilingMs) {

    private static final long MIN_INITIALIZE_TIMEOUT_MS = 60_000;

    public static ProtocolSettings from(ClaudeAgentOptions options) {
        Map<String, McpServer> sdkServers = new LinkedHashMap<>();
        options.mcpServers().forEach((name, config) -> {
            if (config instanceof McpServerConfig.Sdk sdk) {
                sdkServers.put(name, sdk.instance());
            }
        });
        Map<String, Object> agents = new LinkedHashMap<>();
        options.agents().forEach((name, agent) -> agents.put(name, agent.toWire()));

        Boolean excludeDynamicSections = null;
        Boolean snapshot = null;
        switch (options.systemPrompt()) {
            case SystemPrompt.Preset preset -> {
                excludeDynamicSections = preset.excludeDynamicSections();
                snapshot = preset.snapshot();
            }
            case SystemPrompt.Custom custom -> snapshot = custom.snapshot();
            case null, default -> {
            }
        }
        List<String> skills = options.skills() instanceof Skills.Named named ? named.names() : null;

        return new ProtocolSettings(
                options.canUseTool(),
                options.hooks(),
                Map.copyOf(sdkServers),
                initializeTimeout(),
                agents,
                excludeDynamicSections,
                snapshot,
                skills,
                options.forwardSubagentText(),
                options.verbatimPrompts(),
                RunLifecycle.ceilingFromEnv(options.env()));
    }

    /** {@code CLAUDE_CODE_STREAM_CLOSE_TIMEOUT}, but at least 60 s (MCP servers may be slow to start). */
    private static long initializeTimeout() {
        String raw = System.getenv("CLAUDE_CODE_STREAM_CLOSE_TIMEOUT");
        long millis = MIN_INITIALIZE_TIMEOUT_MS;
        if (raw != null) {
            try {
                millis = Long.parseLong(raw.strip());
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        return Math.max(millis, MIN_INITIALIZE_TIMEOUT_MS);
    }
}
