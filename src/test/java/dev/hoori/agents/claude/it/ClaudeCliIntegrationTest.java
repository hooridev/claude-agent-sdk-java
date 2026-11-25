package dev.hoori.agents.claude.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hoori.agents.claude.ClaudeAgent;
import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.ClaudeSdkClient;
import dev.hoori.agents.claude.MessageStream;
import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookMatcher;
import dev.hoori.agents.claude.hook.HookOutput;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.mcp.SdkMcpTool;
import dev.hoori.agents.claude.mcp.ToolResult;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;
import dev.hoori.agents.claude.options.EffortLevel;
import dev.hoori.agents.claude.options.Tools;
import dev.hoori.agents.claude.permission.PermissionResult;

/**
 * Runs against the locally installed {@code claude} CLI. Only with {@code mvn -Pintegration test};
 * uses a cheap model (Haiku, effort low) to keep the cost per run around a few cents.
 */
@Tag("integration")
@Timeout(180)
class ClaudeCliIntegrationTest {

    static final String MODEL = System.getProperty("claude.it.model", "claude-haiku-5-5");

    private static ClaudeAgentOptions.Builder cheap() {
        return ClaudeAgentOptions.builder().model(MODEL).effort(EffortLevel.LOW).tools(Tools.of());
    }

    private static List<Message> drain(MessageStream stream) {
        List<Message> messages = new ArrayList<>();
        try (stream) {
            stream.forEach(messages::add);
        }
        return messages;
    }

    @Test
    void oneShotQuery() {
        List<Message> messages = drain(ClaudeAgent.query("Reply with exactly the word PONG.", cheap().maxTurns(1).build()));
        ResultMessage result = (ResultMessage) messages.getLast();
        assertFalse(result.isError());
        assertTrue(result.result().contains("PONG"), result.result());
        assertTrue(messages.stream().anyMatch(m -> m instanceof AssistantMessage a && a.model().contains("haiku")));
    }

    @Test
    void clientWithSdkToolHookAndPermissionCallback() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger hookCalls = new AtomicInteger();
        AtomicInteger permissionCalls = new AtomicInteger();
        SdkMcpTool add = SdkMcpTool.builder("add", "Add two numbers").param("a", "number").param("b", "number")
                .handler(args -> {
                    toolCalls.incrementAndGet();
                    double sum = ((Number) args.get("a")).doubleValue() + ((Number) args.get("b")).doubleValue();
                    return ToolResult.text(String.valueOf((long) sum));
                })
                .build();
        ClaudeAgentOptions options = cheap().maxTurns(4)
                .mcpServer("calc", McpServerConfig.sdk(SdkMcpServer.builder("calc").tool(add).build()))
                .canUseTool((tool, input, ctx) -> {
                    permissionCalls.incrementAndGet();
                    return PermissionResult.allow();
                })
                .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of("mcp__calc__add", (input, id, ctx) -> {
                    hookCalls.incrementAndGet();
                    return HookOutput.empty();
                }))
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
            client.connect();
            assertNotNull(client.getServerInfo());
            client.query("Use the add tool to compute 17 + 25. Answer with just the number.");
            ResultMessage first = (ResultMessage) drain(client.receiveResponse()).getLast();
            assertTrue(first.result().contains("42"), first.result());

            client.query("Reply with exactly: OK");
            ResultMessage second = (ResultMessage) drain(client.receiveResponse()).getLast();
            assertFalse(second.isError());

            List<Map<String, Object>> servers = (List<Map<String, Object>>) client.getMcpStatus().get("mcpServers");
            assertTrue(servers.stream().anyMatch(s -> "calc".equals(s.get("name")) && "connected".equals(s.get("status"))));
        }
        assertEquals(1, toolCalls.get());
        assertEquals(1, hookCalls.get());
        assertEquals(1, permissionCalls.get());
    }

    @Test
    void structuredOutput() {
        Map<String, Object> schema = Map.of("type", "object",
                "properties", Map.of("capital", Map.of("type", "string")), "required", List.of("capital"));
        List<Message> messages = drain(ClaudeAgent.query("What is the capital of France?",
                cheap().maxTurns(3).jsonSchemaOutput(schema).build()));
        ResultMessage result = (ResultMessage) messages.getLast();
        assertFalse(result.isError(), String.valueOf(result.errors()));
        assertEquals("Paris", ((Map<?, ?>) result.structuredOutput()).get("capital"));
    }
}
