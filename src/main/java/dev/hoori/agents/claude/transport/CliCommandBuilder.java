package dev.hoori.agents.claude.transport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.options.PluginConfig;
import dev.hoori.agents.claude.options.SettingSource;
import dev.hoori.agents.claude.options.Skills;
import dev.hoori.agents.claude.options.SystemPrompt;
import dev.hoori.agents.claude.options.ThinkingConfig;
import dev.hoori.agents.claude.options.Tools;

/** Translates {@link ClaudeAgentOptions} into the CLI's command line (Python: {@code _build_command}). */
final class CliCommandBuilder {

    /** cmd.exe metacharacters, plus the quote and {@code !} (delayed expansion). */
    private static final String CMD_EXE_METACHARACTERS = "&|<>^%!\"";
    /** Delimiters of the --allowedTools tokenizer, control characters and the BOM. */
    private static final Pattern SKILL_NAME_INVALID_CHARS = Pattern.compile("[(),\\x00-\\x1f\\x7f-\\x9f\\ufeff]");

    private final ClaudeAgentOptions options;
    private final boolean windows;

    CliCommandBuilder(ClaudeAgentOptions options) {
        this(options, CliLocator.isWindows());
    }

    CliCommandBuilder(ClaudeAgentOptions options, boolean windows) {
        this.options = options;
        this.windows = windows;
    }

    List<String> build(String cliPath) {
        List<String> cmd = new ArrayList<>(List.of(cliPath, "--output-format", "stream-json", "--verbose"));
        addSystemPrompt(cmd);
        addTools(cmd);

        List<String> allowedTools = new ArrayList<>(options.allowedTools());
        List<String> settingSources = options.settingSources() == null ? null
                : options.settingSources().stream().map(SettingSource::wireValue).toList();
        settingSources = applySkillsDefaults(allowedTools, settingSources);

        if (!allowedTools.isEmpty()) {
            cmd.addAll(List.of("--allowedTools", String.join(",", allowedTools)));
        }
        if (options.maxTurns() != null && options.maxTurns() != 0) {
            cmd.addAll(List.of("--max-turns", options.maxTurns().toString()));
        }
        if (options.maxBudgetUsd() != null) {
            cmd.addAll(List.of("--max-budget-usd", options.maxBudgetUsd().toString()));
        }
        if (!options.disallowedTools().isEmpty()) {
            cmd.addAll(List.of("--disallowedTools", String.join(",", options.disallowedTools())));
        }
        if (options.taskBudget() != null) {
            cmd.addAll(List.of("--task-budget", options.taskBudget().toString()));
        }
        addIfPresent(cmd, "--model", options.model());
        addIfPresent(cmd, "--fallback-model", options.fallbackModel());
        if (!options.betas().isEmpty()) {
            cmd.addAll(List.of("--betas", String.join(",", options.betas())));
        }
        addIfPresent(cmd, "--permission-prompt-tool", options.permissionPromptToolName());
        if (options.permissionMode() != null) {
            cmd.addAll(List.of("--permission-mode", options.permissionMode().wireValue()));
        }
        if (options.continueConversation()) {
            cmd.add("--continue");
        }
        // Equals form: the CLI declares --resume with an optional value, so in the two-token form a
        // dash-leading value would be parsed as a separate flag (flag injection from untrusted input).
        addEquals(cmd, "resume", options.resume());
        addEquals(cmd, "session-id", options.sessionId());

        String settingsValue = buildSettingsValue();
        if (settingsValue != null && !settingsValue.isEmpty()) {
            cmd.addAll(List.of("--settings", settingsValue));
        }
        for (Path dir : options.addDirs()) {
            cmd.addAll(List.of("--add-dir", dir.toString()));
        }
        addMcpConfig(cmd);
        if (options.includePartialMessages()) {
            cmd.add("--include-partial-messages");
        }
        if (options.includeHookEvents()) {
            cmd.add("--include-hook-events");
        }
        if (options.strictMcpConfig()) {
            cmd.add("--strict-mcp-config");
        }
        if (options.forkSession()) {
            cmd.add("--fork-session");
        }
        addEquals(cmd, "resume-session-at", options.resumeSessionAt());
        // Forwarded even when empty, so the CLI rejects it instead of the SDK silently disarming the guard.
        if (options.resumeDropsTurn() != null) {
            rejectWindowsCmdMetacharacters("resume-drops-turn", options.resumeDropsTurn());
            cmd.add("--resume-drops-turn=" + options.resumeDropsTurn());
        }
        if (options.sessionStore() != null) {
            cmd.add("--session-mirror");
        }
        // Agents are sent with the initialize request, not as a flag.
        if (settingSources != null) {
            cmd.add("--setting-sources=" + String.join(",", settingSources));
        }
        for (PluginConfig plugin : options.plugins()) {
            if (!"local".equals(plugin.type())) {
                throw new IllegalArgumentException("Unsupported plugin type: " + plugin.type());
            }
            cmd.addAll(List.of("--plugin-dir", plugin.path()));
        }
        addExtraArgs(cmd);
        addThinking(cmd);
        if (options.effort() != null) {
            cmd.addAll(List.of("--effort", options.effort().wireValue()));
        }
        Map<String, Object> outputFormat = options.outputFormat();
        if (outputFormat != null && "json_schema".equals(outputFormat.get("type"))
                && outputFormat.get("schema") != null) {
            cmd.addAll(List.of("--json-schema", Json.write(outputFormat.get("schema"))));
        }
        // Always streaming input: agents and other large configs go through the initialize request.
        cmd.addAll(List.of("--input-format", "stream-json"));
        return cmd;
    }

    private void addSystemPrompt(List<String> cmd) {
        switch (options.systemPrompt()) {
            case null -> cmd.addAll(List.of("--system-prompt", ""));
            case SystemPrompt.Custom custom -> cmd.addAll(List.of("--system-prompt", custom.prompt()));
            case SystemPrompt.File file -> cmd.addAll(List.of("--system-prompt-file", file.path()));
            case SystemPrompt.Preset preset -> {
                if (preset.append() != null) {
                    cmd.addAll(List.of("--append-system-prompt", preset.append()));
                }
            }
        }
    }

    private void addTools(List<String> cmd) {
        switch (options.tools()) {
            case null -> {
            }
            case Tools.Named named -> cmd.addAll(List.of("--tools", String.join(",", named.names())));
            case Tools.Preset _ -> cmd.addAll(List.of("--tools", "default"));
        }
    }

    private void addMcpConfig(List<String> cmd) {
        if (options.mcpConfig() != null) {
            cmd.addAll(List.of("--mcp-config", options.mcpConfig()));
            return;
        }
        if (options.mcpServers().isEmpty()) {
            return;
        }
        Map<String, Object> servers = new LinkedHashMap<>();
        options.mcpServers().forEach((name, config) -> servers.put(name, config.toCliConfig()));
        cmd.addAll(List.of("--mcp-config", Json.write(Map.of("mcpServers", servers))));
    }

    private void addExtraArgs(List<String> cmd) {
        options.extraArgs().forEach((flag, value) -> {
            if (value == null) {
                cmd.add("--" + flag);
            } else if (value.startsWith("-")) {
                // A dash-leading value would be parsed as a separate flag in the two-token form.
                cmd.add("--" + flag + "=" + value);
            } else {
                cmd.addAll(List.of("--" + flag, value));
            }
        });
    }

    private void addThinking(List<String> cmd) {
        ThinkingConfig thinking = options.thinking();
        if (thinking == null) {
            if (options.maxThinkingTokens() != null) {
                cmd.addAll(List.of("--max-thinking-tokens", options.maxThinkingTokens().toString()));
            }
            return;
        }
        String display = switch (thinking) {
            case ThinkingConfig.Adaptive adaptive -> {
                cmd.addAll(List.of("--thinking", "adaptive"));
                yield adaptive.display();
            }
            case ThinkingConfig.Enabled enabled -> {
                cmd.addAll(List.of("--max-thinking-tokens", Integer.toString(enabled.budgetTokens())));
                yield enabled.display();
            }
            case ThinkingConfig.Disabled _ -> {
                cmd.addAll(List.of("--thinking", "disabled"));
                yield null;
            }
        };
        if (display != null) {
            cmd.addAll(List.of("--thinking-display", display));
        }
    }

    private static void addIfPresent(List<String> cmd, String flag, String value) {
        if (value != null && !value.isEmpty()) {
            cmd.addAll(List.of(flag, value));
        }
    }

    private void addEquals(List<String> cmd, String flag, String value) {
        if (value != null && !value.isEmpty()) {
            rejectWindowsCmdMetacharacters(flag, value);
            cmd.add("--" + flag + "=" + value);
        }
    }

    // ------------------------------------------------------------------ settings + sandbox

    /** Merges {@code sandbox} into the settings. Settings that cannot be loaded fail the connect. */
    String buildSettingsValue() {
        String settings = options.settings();
        if (options.sandbox() == null) {
            return settings;
        }
        try {
            Map<String, Object> merged = new LinkedHashMap<>();
            if (settings != null) {
                String trimmed = settings.strip();
                if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) {
                    // A file path: read it like the CLI does, relative to its cwd and with or without BOM.
                    Path base = options.cwd() == null ? Path.of("") : options.cwd();
                    trimmed = Files.readString(base.resolve(trimmed), StandardCharsets.UTF_8);
                    if (trimmed.startsWith("﻿")) {
                        trimmed = trimmed.substring(1);
                    }
                }
                merged.putAll(Json.parseObject(trimmed));
            }
            merged.put("sandbox", options.sandbox());
            return Json.write(merged);
        } catch (IOException | RuntimeException e) {
            throw new CliConnectionException("Failed to load settings: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ skills

    /**
     * Injects {@code Skill} / {@code Skill(name)} into {@code allowedTools} and defaults the
     * setting sources to {@code user, project} so installed skills are discovered.
     */
    private List<String> applySkillsDefaults(List<String> allowedTools, List<String> settingSources) {
        Skills skills = options.skills();
        if (skills == null) {
            return settingSources;
        }
        switch (skills) {
            case Skills.All _ -> {
                if (!allowedTools.contains("Skill")) {
                    allowedTools.add("Skill");
                }
            }
            case Skills.Named named -> {
                for (String name : named.names()) {
                    validateSkillName(name);
                    String rule = "Skill(" + name + ")";
                    if (!allowedTools.contains(rule)) {
                        allowedTools.add(rule);
                    }
                }
            }
        }
        return settingSources != null ? settingSources : List.of("user", "project");
    }

    /**
     * Rejects skill names that cannot ride safely in a {@code Skill(name)} rule: the
     * --allowedTools tokenizer splits on commas and spaces outside parentheses and honors no escapes.
     */
    static void validateSkillName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Skill names must be strings, got null");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Skill names must be non-empty strings");
        }
        if (name.codePoints().anyMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE)) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': contains an unpaired surrogate, which can never match a skill the CLI discovered.");
        }
        if (!name.equals(name.strip())) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': leading or trailing whitespace can never match - the Skill tool trims the invoked name.");
        }
        if (SKILL_NAME_INVALID_CHARS.matcher(name).find()) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': parentheses, commas, control characters, and byte-order marks are not allowed. Names "
                    + "match the skill's directory name, or 'plugin:skill' for plugin-qualified skills.");
        }
        if (name.equals("*")) {
            throw new IllegalArgumentException("Invalid skill name '*': use Skills.all() to enable every skill.");
        }
        if (name.endsWith(":*") || name.endsWith(" *")) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': wildcard-suffix names are not allowed; list each skill by its exact name.");
        }
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': skill names may not start with '/'. Use the canonical name, not the slash-command form.");
        }
        if (name.contains("\\\\")) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': consecutive backslashes are not allowed - the per-rule parser collapses them.");
        }
        if (name.endsWith("\\")) {
            throw new IllegalArgumentException("Invalid skill name '" + name
                    + "': names may not end with an unpaired backslash.");
        }
    }

    // ------------------------------------------------------------------ Windows hardening

    /**
     * Defense in depth: values commonly taken from external input stay inert even if a cmd.exe
     * hop is ever introduced between the SDK and the CLI. No effect on POSIX.
     */
    void rejectWindowsCmdMetacharacters(String optionName, String value) {
        if (!windows) {
            return;
        }
        TreeSet<Character> bad = new TreeSet<>();
        for (char c : value.toCharArray()) {
            if (CMD_EXE_METACHARACTERS.indexOf(c) >= 0 || c == '\r' || c == '\n') {
                bad.add(c);
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException(optionName + " value '" + value
                    + "' contains characters that are unsafe to pass on a Windows command line: " + bad);
        }
    }

    /**
     * Whether {@code cliPath} names a .bat/.cmd batch script, checking every path component and
     * NTFS stream segment (see the Python SDK's {@code _is_windows_batch_cli}).
     */
    static boolean isWindowsBatchCli(String cliPath) {
        for (String component : cliPath.replace('\\', '/').split("/")) {
            for (String segment : component.split(":")) {
                String trimmed = segment.replaceAll("[. ]+$", "").toLowerCase(Locale.ROOT);
                if (trimmed.endsWith(".bat") || trimmed.endsWith(".cmd")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Refuses to run a batch script on Windows: cmd.exe re-parses the command line, so arguments
     * could inject commands and no reliable escaping exists (CVE-2024-27980, "BatBadBut").
     */
    void rejectWindowsBatchCli(String cliPath) {
        if (windows && isWindowsBatchCli(cliPath)) {
            throw new CliConnectionException("Refusing to execute batch script '" + cliPath
                    + "': Windows runs .bat/.cmd files via cmd.exe, which can execute commands injected through "
                    + "CLI arguments. Use a native claude.exe instead (irm https://claude.ai/install.ps1 | iex).");
        }
    }
}
