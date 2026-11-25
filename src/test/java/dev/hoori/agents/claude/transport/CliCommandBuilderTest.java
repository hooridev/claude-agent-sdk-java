package dev.hoori.agents.claude.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.options.EffortLevel;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.options.PluginConfig;
import dev.hoori.agents.claude.options.SettingSource;
import dev.hoori.agents.claude.options.Skills;
import dev.hoori.agents.claude.options.SystemPrompt;
import dev.hoori.agents.claude.options.ThinkingConfig;
import dev.hoori.agents.claude.options.Tools;

class CliCommandBuilderTest {

    private static List<String> build(ClaudeAgentOptions options) {
        return new CliCommandBuilder(options, false).build("claude");
    }

    private static String valueAfter(List<String> cmd, String flag) {
        int index = cmd.indexOf(flag);
        assertTrue(index >= 0, () -> flag + " missing in " + cmd);
        return cmd.get(index + 1);
    }

    @Test
    void defaultCommand() {
        assertEquals(List.of("claude", "--output-format", "stream-json", "--verbose", "--system-prompt", "",
                "--input-format", "stream-json"), build(ClaudeAgentOptions.defaults()));
    }

    @Test
    void systemPromptVariants() {
        assertEquals("be brief", valueAfter(build(ClaudeAgentOptions.builder().systemPrompt("be brief").build()),
                "--system-prompt"));
        List<String> preset = build(ClaudeAgentOptions.builder().systemPrompt(SystemPrompt.claudeCode("extra")).build());
        assertEquals("extra", valueAfter(preset, "--append-system-prompt"));
        assertFalse(preset.contains("--system-prompt"));
        assertFalse(build(ClaudeAgentOptions.builder().systemPrompt(SystemPrompt.claudeCode()).build())
                .contains("--append-system-prompt"));
        assertEquals("/p.md", valueAfter(build(ClaudeAgentOptions.builder()
                .systemPrompt(SystemPrompt.file("/p.md")).build()), "--system-prompt-file"));
    }

    @Test
    void scalarAndListOptions() {
        List<String> cmd = build(ClaudeAgentOptions.builder()
                .tools(Tools.of()).allowedTools("Read", "Bash(ls:*)").disallowedTools("Write")
                .maxTurns(3).maxBudgetUsd(1.5).model("claude-haiku-5-5").fallbackModel("sonnet")
                .betas("context-1m-2025-08-07").permissionMode(PermissionMode.ACCEPT_EDITS)
                .continueConversation(true).addDirs(Path.of("/a"), Path.of("/b"))
                .includePartialMessages(true).includeHookEvents(true).strictMcpConfig(true).forkSession(true)
                .settingSources(SettingSource.USER).plugins(PluginConfig.local("/plug"))
                .effort(EffortLevel.LOW).taskBudget(1000)
                .build());
        assertEquals("", valueAfter(cmd, "--tools"));
        assertEquals("Read,Bash(ls:*)", valueAfter(cmd, "--allowedTools"));
        assertEquals("Write", valueAfter(cmd, "--disallowedTools"));
        assertEquals("3", valueAfter(cmd, "--max-turns"));
        assertEquals("1.5", valueAfter(cmd, "--max-budget-usd"));
        assertEquals("claude-haiku-5-5", valueAfter(cmd, "--model"));
        assertEquals("acceptEdits", valueAfter(cmd, "--permission-mode"));
        assertEquals("low", valueAfter(cmd, "--effort"));
        assertEquals("1000", valueAfter(cmd, "--task-budget"));
        assertEquals("/plug", valueAfter(cmd, "--plugin-dir"));
        assertTrue(cmd.contains("--setting-sources=user"));
        for (String flag : List.of("--continue", "--include-partial-messages", "--include-hook-events",
                "--strict-mcp-config", "--fork-session")) {
            assertTrue(cmd.contains(flag), flag);
        }
        assertEquals(List.of("--add-dir", "/a", "--add-dir", "/b"),
                cmd.subList(cmd.indexOf("--add-dir"), cmd.indexOf("--add-dir") + 4));
        assertEquals("default", valueAfter(build(ClaudeAgentOptions.builder().tools(Tools.claudeCode()).build()),
                "--tools"));
    }

    @Test
    void untrustedValuesUseTheEqualsForm() {
        List<String> cmd = build(ClaudeAgentOptions.builder().resume("--dangerous").sessionId("sid")
                .resumeSessionAt("m1").resumeDropsTurn("").extraArg("debug-to", "-x").extraArg("foo", "bar")
                .extraFlag("flag").build());
        assertTrue(cmd.contains("--resume=--dangerous"));
        assertTrue(cmd.contains("--session-id=sid"));
        assertTrue(cmd.contains("--resume-session-at=m1"));
        assertTrue(cmd.contains("--resume-drops-turn="), "forwarded even when empty");
        assertTrue(cmd.contains("--debug-to=-x"));
        assertEquals("bar", valueAfter(cmd, "--foo"));
        assertTrue(cmd.contains("--flag"));
    }

    @Test
    void thinkingConfig() {
        assertEquals("adaptive", valueAfter(build(ClaudeAgentOptions.builder()
                .thinking(ThinkingConfig.adaptive()).build()), "--thinking"));
        List<String> enabled = build(ClaudeAgentOptions.builder()
                .thinking(new ThinkingConfig.Enabled(2048, "summarized")).maxThinkingTokens(1).build());
        assertEquals("2048", valueAfter(enabled, "--max-thinking-tokens"));
        assertEquals("summarized", valueAfter(enabled, "--thinking-display"));
        assertEquals("99", valueAfter(build(ClaudeAgentOptions.builder().maxThinkingTokens(99).build()),
                "--max-thinking-tokens"));
    }

    @Test
    void jsonSchemaAndMcpConfig() {
        var sdk = McpServerConfig.sdk(SdkMcpServer.builder("calc").build());
        List<String> cmd = build(ClaudeAgentOptions.builder()
                .jsonSchemaOutput(Map.of("type", "object"))
                .mcpServer("calc", sdk)
                .mcpServer("remote", McpServerConfig.http("https://x").withHeaders(Map.of("A", "b")))
                .build());
        assertEquals("{\"type\":\"object\"}", valueAfter(cmd, "--json-schema"));
        Map<String, Object> servers = Json.object(Json.parseObject(valueAfter(cmd, "--mcp-config")), "mcpServers");
        assertEquals(Map.of("type", "sdk", "name", "calc"), servers.get("calc"));
        assertEquals(Map.of("type", "http", "url", "https://x", "headers", Map.of("A", "b")), servers.get("remote"));
        assertEquals("/cfg.json", valueAfter(build(ClaudeAgentOptions.builder().mcpConfig("/cfg.json").build()),
                "--mcp-config"));
        assertThrows(IllegalStateException.class,
                () -> ClaudeAgentOptions.builder().mcpConfig("/x").mcpServer("a", sdk).build());
    }

    @Test
    void skillsInjectAllowedToolsAndDefaultSettingSources() {
        List<String> all = build(ClaudeAgentOptions.builder().skills(Skills.all()).build());
        assertEquals("Skill", valueAfter(all, "--allowedTools"));
        assertTrue(all.contains("--setting-sources=user,project"));
        List<String> named = build(ClaudeAgentOptions.builder().skills(Skills.of("pdf", "plugin:x"))
                .allowedTools("Skill(pdf)").settingSources(SettingSource.LOCAL).build());
        assertEquals("Skill(pdf),Skill(plugin:x)", valueAfter(named, "--allowedTools"));
        assertTrue(named.contains("--setting-sources=local"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " pdf", "a,b", "a(b)", "*", "x:*", "/pdf", "a\\\\b", "a\\", "a\u0001", "\uFEFFa"})
    void invalidSkillNamesAreRejected(String name) {
        assertThrows(IllegalArgumentException.class, () -> CliCommandBuilder.validateSkillName(name));
    }

    @Test
    void validSkillNamesPass() {
        CliCommandBuilder.validateSkillName("pdf");
        CliCommandBuilder.validateSkillName("plugin:skill-name_1");
        CliCommandBuilder.validateSkillName("emoji-\uD83D\uDE00");
    }

    @Test
    void sandboxIsMergedIntoSettings(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "\uFEFF{\"permissions\":{\"allow\":[\"Read\"]}}");
        List<String> cmd = build(ClaudeAgentOptions.builder().cwd(dir).settings("settings.json")
                .sandbox(Map.of("enabled", true)).build());
        Map<String, Object> settings = Json.parseObject(valueAfter(cmd, "--settings"));
        assertEquals(Map.of("enabled", true), settings.get("sandbox"));
        assertEquals(Map.of("allow", List.of("Read")), settings.get("permissions"));
        assertEquals("{\"a\":1}", valueAfter(build(ClaudeAgentOptions.builder().settings("{\"a\":1}").build()),
                "--settings"));
        assertThrows(CliConnectionException.class, () -> build(ClaudeAgentOptions.builder().settings("missing.json")
                .sandbox(Map.of()).cwd(dir).build()));
    }

    @Test
    void windowsHardening() {
        assertTrue(CliCommandBuilder.isWindowsBatchCli("C:\\npm\\claude.cmd"));
        assertTrue(CliCommandBuilder.isWindowsBatchCli("claude.BAT. "));
        assertTrue(CliCommandBuilder.isWindowsBatchCli("claude.cmd:stream"));
        assertTrue(CliCommandBuilder.isWindowsBatchCli("dir.cmd\\claude.exe"));
        assertFalse(CliCommandBuilder.isWindowsBatchCli("C:\\bin\\claude.exe"));
        var windows = new CliCommandBuilder(ClaudeAgentOptions.builder().resume("a&b").build(), true);
        assertThrows(IllegalArgumentException.class, () -> windows.build("claude.exe"));
        assertThrows(CliConnectionException.class, () -> windows.rejectWindowsBatchCli("claude.cmd"));
        new CliCommandBuilder(ClaudeAgentOptions.builder().resume("a&b").build(), false).build("claude");
    }
}
