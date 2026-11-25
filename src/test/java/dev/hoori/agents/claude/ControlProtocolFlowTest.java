package dev.hoori.agents.claude;

import static dev.hoori.agents.claude.FakeTransport.assistant;
import static dev.hoori.agents.claude.FakeTransport.controlRequest;
import static dev.hoori.agents.claude.FakeTransport.isControlRequest;
import static dev.hoori.agents.claude.FakeTransport.result;
import static dev.hoori.agents.claude.FakeTransport.sessionState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.exception.ProcessException;
import dev.hoori.agents.claude.exception.ResultException;
import dev.hoori.agents.claude.hook.HookEvent;
import dev.hoori.agents.claude.hook.HookFailureMode;
import dev.hoori.agents.claude.hook.HookMatcher;
import dev.hoori.agents.claude.hook.HookOutput;
import dev.hoori.agents.claude.hook.HookSpecificOutput;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.mcp.SdkMcpTool;
import dev.hoori.agents.claude.mcp.ToolResult;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;
import dev.hoori.agents.claude.message.SystemMessage;
import dev.hoori.agents.claude.options.AgentDefinition;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.permission.PermissionBehavior;
import dev.hoori.agents.claude.permission.PermissionResult;
import dev.hoori.agents.claude.permission.PermissionRuleValue;
import dev.hoori.agents.claude.permission.PermissionUpdate;

@Timeout(20)
class ControlProtocolFlowTest {

    private static List<Message> drain(MessageStream stream) {
        List<Message> messages = new ArrayList<>();
        stream.forEach(messages::add);
        return messages;
    }

    @Test
    void queryWritesPromptAfterInitializeAndClosesStdinAfterResult() throws Exception {
        FakeTransport cli = new FakeTransport().answeringPrompts("hi");
        cli.onWrite((message, fake) -> {
            if ("user".equals(message.get("type"))) {
                fake.finish();
            }
        });
        List<Message> messages = drain(ClaudeAgent.query("hello", ClaudeAgentOptions.defaults(), cli));

        assertEquals(2, messages.size());
        assertEquals("hi", ((AssistantMessage) messages.get(0)).text());
        assertInstanceOf(ResultMessage.class, messages.get(1));
        assertTrue(cli.stdinClosed.await(2, TimeUnit.SECONDS));
        assertTrue(cli.closed, "iterating to the end closes the transport");

        Map<String, Object> init = cli.written.getFirst();
        assertTrue(isControlRequest(init, "initialize"));
        Map<String, Object> user = cli.writtenOfType("user").getFirst();
        assertEquals(Map.of("role", "user", "content", "hello"), user.get("message"));
        assertEquals("", user.get("session_id"));
    }

    @Test
    void initializeCarriesAgentsHooksAndVerbatimStamp() throws Exception {
        FakeTransport cli = new FakeTransport().answeringPrompts("x");
        var options = ClaudeAgentOptions.builder()
                .agent("reviewer", AgentDefinition.builder("Reviews code", "You review.").tools("Read").build())
                .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of("Bash", (in, id, ctx) -> HookOutput.empty())
                        .withTimeout(5))
                .verbatimPrompts(true)
                .build();
        try (MessageStream _ = ClaudeAgent.query("hi", options, cli)) {
            Map<String, Object> request = Json.object(cli.written.getFirst(), "request");
            Map<String, Object> agents = Json.object(request, "agents");
            assertEquals(Map.of("description", "Reviews code", "prompt", "You review.", "tools", List.of("Read")),
                    agents.get("reviewer"));
            Map<String, Object> hooks = Json.object(request, "hooks");
            Map<String, Object> matcher = Json.objectList(hooks, "PreToolUse").getFirst();
            assertEquals("Bash", matcher.get("matcher"));
            assertEquals(List.of("hook_0"), matcher.get("hookCallbackIds"));
            assertEquals(5.0, ((Number) matcher.get("timeout")).doubleValue());
            assertEquals(true, cli.writtenOfType("user").getFirst().get("client_composed"));
        }
    }

    @Test
    void initializeWithoutHooksSendsNullHooks() {
        FakeTransport cli = new FakeTransport();
        try (ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults(), cli)) {
            client.connect();
            Map<String, Object> request = Json.object(cli.written.getFirst(), "request");
            assertTrue(request.containsKey("hooks"));
            assertNull(request.get("hooks"));
        }
    }

    @Test
    void blockingHooksRequireCliSupport() {
        FakeTransport cli = new FakeTransport();
        var options = ClaudeAgentOptions.builder()
                .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of((in, id, ctx) -> HookOutput.empty())
                        .withOnFailure(HookFailureMode.BLOCK))
                .build();
        ClaudeSdkClient client = new ClaudeSdkClient(options, cli);
        assertThrows(CliConnectionException.class, client::connect);
        assertTrue(cli.closed, "a failed connect cleans up");
        assertFalse(client.isConnected());
    }

    @Test
    void permissionCallbackAllowsWithUpdatesAndDenies() throws Exception {
        FakeTransport cli = new FakeTransport();
        var options = ClaudeAgentOptions.builder()
                .canUseTool((tool, input, ctx) -> "Bash".equals(tool)
                        ? PermissionResult.deny("no shell", true)
                        : PermissionResult.allow(Map.of("path", "/safe")).withUpdatedPermissions(
                        PermissionUpdate.addRules(PermissionBehavior.ALLOW, PermissionRuleValue.of("Read"))))
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options, cli)) {
            client.connect();
            cli.emit(controlRequest("r1", Map.of("subtype", "can_use_tool", "tool_name", "Read",
                    "input", Map.of("path", "/etc"), "tool_use_id", "t1",
                    "permission_suggestions", List.of(Map.of("type", "setMode", "mode", "acceptEdits")))));
            cli.emit(controlRequest("r2", Map.of("subtype", "can_use_tool", "tool_name", "Bash",
                    "input", Map.of("command", "rm -rf /"))));

            Map<String, Object> allow = responsePayload(cli, "r1");
            assertEquals("allow", allow.get("behavior"));
            assertEquals(Map.of("path", "/safe"), allow.get("updatedInput"));
            assertEquals(List.of(Map.of("type", "addRules", "destination", "session", "behavior", "allow",
                    "rules", List.of(Map.of("toolName", "Read")))), allow.get("updatedPermissions"));

            Map<String, Object> deny = responsePayload(cli, "r2");
            assertEquals(Map.of("behavior", "deny", "message", "no shell", "interrupt", true), deny);
        }
    }

    @Test
    void canUseToolRoutesPromptsOverStdioAndRejectsPromptToolName() {
        var conflicting = ClaudeAgentOptions.builder()
                .canUseTool((t, i, c) -> PermissionResult.allow()).permissionPromptToolName("mcp__x__y").build();
        assertThrows(IllegalArgumentException.class,
                () -> new ClaudeSdkClient(conflicting, new FakeTransport()).connect());
    }

    @Test
    void hookCallbackReceivesInputAndReturnsOutput() throws Exception {
        FakeTransport cli = new FakeTransport();
        var options = ClaudeAgentOptions.builder()
                .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of((input, toolUseId, ctx) -> HookOutput.builder()
                        .continueExecution(false)
                        .hookSpecificOutput(HookSpecificOutput.preToolUse("deny", input.toolName() + "/" + toolUseId))
                        .build()))
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options, cli)) {
            client.connect();
            cli.emit(controlRequest("h1", Map.of("subtype", "hook_callback", "callback_id", "hook_0",
                    "tool_use_id", "tu1", "input", Map.of("hook_event_name", "PreToolUse", "tool_name", "Bash"))));
            Map<String, Object> payload = responsePayload(cli, "h1");
            assertEquals(false, payload.get("continue"));
            assertEquals("Bash/tu1", Json.object(payload, "hookSpecificOutput").get("permissionDecisionReason"));
        }
    }

    @Test
    void failingHandlersAnswerWithErrorResponses() throws Exception {
        FakeTransport cli = new FakeTransport();
        var options = ClaudeAgentOptions.builder()
                .hook(HookEvent.STOP, HookMatcher.of((in, id, ctx) -> {
                    throw new IllegalStateException("boom");
                }))
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options, cli)) {
            client.connect();
            cli.emit(controlRequest("e1", Map.of("subtype", "hook_callback", "callback_id", "hook_0", "input", Map.of())));
            cli.emit(controlRequest("e2", Map.of("subtype", "hook_callback", "callback_id", "nope", "input", Map.of())));
            cli.emit(controlRequest("e3", Map.of("subtype", "can_use_tool", "tool_name", "Read", "input", Map.of())));
            cli.emit(controlRequest("e4", Map.of("subtype", "what_is_this")));
            assertEquals("boom", errorOf(cli, "e1"));
            assertEquals("No hook callback found for ID: nope", errorOf(cli, "e2"));
            assertEquals("canUseTool callback is not provided", errorOf(cli, "e3"));
            assertEquals("Unsupported control request subtype: what_is_this", errorOf(cli, "e4"));
        }
    }

    @Test
    void cancelledControlRequestIsNotAnswered() throws Exception {
        FakeTransport cli = new FakeTransport();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var options = ClaudeAgentOptions.builder()
                .canUseTool((tool, input, ctx) -> {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        throw e;
                    }
                    return PermissionResult.allow();
                })
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options, cli)) {
            client.connect();
            cli.emit(controlRequest("c1", Map.of("subtype", "can_use_tool", "tool_name", "Read", "input", Map.of())));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            cli.emit(Map.of("type", "control_cancel_request", "request_id", "c1"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            assertTrue(cli.writtenOfType("control_response").isEmpty(), "no response for a cancelled request");
        }
    }

    @Test
    void sdkMcpServerAnswersToolsListAndCall() throws Exception {
        FakeTransport cli = new FakeTransport();
        SdkMcpTool echo = SdkMcpTool.builder("echo", "Echo text").param("text", "string")
                .handler(args -> ToolResult.text("echo: " + args.get("text"))).build();
        var options = ClaudeAgentOptions.builder()
                .mcpServer("tools", McpServerConfig.sdk(SdkMcpServer.builder("tools").tool(echo).build()))
                .build();
        try (ClaudeSdkClient client = new ClaudeSdkClient(options, cli)) {
            client.connect();
            cli.emit(controlRequest("m1", mcp("tools", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))));
            cli.emit(controlRequest("m2", mcp("tools", Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/call",
                    "params", Map.of("name", "echo", "arguments", Map.of("text", "hey"))))));
            cli.emit(controlRequest("m3", mcp("tools", Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", Map.of("name", "echo", "arguments", Map.of("text", 42))))));
            cli.emit(controlRequest("m4", mcp("missing", Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/list"))));
            cli.emit(controlRequest("m5", mcp("tools", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))));

            Map<String, Object> list = mcpResponse(cli, "m1");
            List<Map<String, Object>> tools = Json.objectList(Json.object(list, "result"), "tools");
            assertEquals("echo", tools.getFirst().get("name"));
            assertEquals(List.of("text"), Json.object(tools.getFirst(), "inputSchema").get("required"));

            Map<String, Object> call = Json.object(mcpResponse(cli, "m2"), "result");
            assertEquals(false, call.get("isError"));
            assertEquals("echo: hey", Json.objectList(call, "content").getFirst().get("text"));

            Map<String, Object> invalid = Json.object(mcpResponse(cli, "m3"), "result");
            assertEquals(true, invalid.get("isError"));
            assertTrue(((String) Json.objectList(invalid, "content").getFirst().get("text"))
                    .startsWith("Input validation error"));

            assertEquals(-32601, ((Number) Json.object(mcpResponse(cli, "m4"), "error").get("code")).intValue());
            assertEquals(Map.of("jsonrpc", "2.0", "result", Map.of()), mcpResponse(cli, "m5"));
        }
    }

    @Test
    void errorResultReplacesProcessExitError() {
        FakeTransport cli = new FakeTransport().onWrite((message, fake) -> {
            if ("user".equals(message.get("type"))) {
                fake.emit(Map.of("type", "result", "subtype", "error_max_turns", "duration_ms", 1,
                        "duration_api_ms", 1, "is_error", true, "num_turns", 3, "session_id", "s9",
                        "errors", List.of(" Reached max turns ", ""), "terminal_reason", "max_turns"));
            }
        });
        // The transport fails like the CLI exiting with code 1 after an error result.
        FakeTransport failing = new FakeTransport() {
            @Override
            public java.util.Iterator<Map<String, Object>> readMessages() {
                java.util.Iterator<Map<String, Object>> inner = cli.readMessages();
                return new java.util.Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        if (inner.hasNext()) {
                            return true;
                        }
                        throw new ProcessException("Command failed with exit code 1", 1, null);
                    }

                    @Override
                    public Map<String, Object> next() {
                        return inner.next();
                    }
                };
            }

            @Override
            public void write(String data) {
                cli.write(data);
                if (data.contains("\"type\":\"user\"")) {
                    cli.finish();
                }
            }
        };
        MessageStream stream = ClaudeAgent.query("go", ClaudeAgentOptions.defaults(), failing);
        List<Message> seen = new ArrayList<>();
        ResultException error = assertThrows(ResultException.class, () -> stream.forEach(seen::add));
        assertEquals(1, seen.size());
        assertEquals("error_max_turns", error.subtype());
        assertEquals("max_turns", error.terminalReason());
        assertEquals(List.of("Reached max turns"), error.errors());
        assertEquals(1, error.exitCode());
        assertTrue(error.getMessage().contains("Reached max turns"));
    }

    @Test
    void controlRequestErrorsAndClientCommands() {
        FakeTransport cli = new FakeTransport().onWrite((message, fake) -> {
            if (isControlRequest(message, "interrupt")) {
                fake.respondError(message, "nothing to interrupt");
            } else if (isControlRequest(message, "mcp_status")) {
                fake.respondSuccess(message, Map.of("mcpServers", List.of()));
            } else if ("control_request".equals(message.get("type")) && !isControlRequest(message, "initialize")) {
                fake.respondSuccess(message, Map.of());
            }
        });
        cli.initializeResponse = Map.of("commands", List.of());
        try (ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults(), cli)) {
            client.connect();
            assertEquals(Map.of("commands", List.of()), client.getServerInfo());
            ClaudeSdkException error = assertThrows(ClaudeSdkException.class, client::interrupt);
            assertEquals("nothing to interrupt", error.getMessage());
            assertEquals(Map.of("mcpServers", List.of()), client.getMcpStatus());
            client.setPermissionMode(PermissionMode.ACCEPT_EDITS);
            client.setModel(null);
            client.stopTask("task-1");
        }
        Map<String, Object> setMode = requestOf(cli, "set_permission_mode");
        assertEquals("acceptEdits", setMode.get("mode"));
        Map<String, Object> setModel = requestOf(cli, "set_model");
        assertTrue(setModel.containsKey("model"));
        assertNull(setModel.get("model"));
        assertEquals("task-1", requestOf(cli, "stop_task").get("task_id"));
    }

    @Test
    void pendingControlRequestFailsWhenTheConnectionCloses() throws Exception {
        FakeTransport cli = new FakeTransport(); // never answers mcp_status
        ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults(), cli).connect();
        java.util.concurrent.CompletableFuture<Object> status =
                java.util.concurrent.CompletableFuture.supplyAsync(client::getMcpStatus);
        cli.awaitWritten(m -> isControlRequest(m, "mcp_status"));
        client.close();
        java.util.concurrent.ExecutionException error = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> status.get(2, TimeUnit.SECONDS));
        assertInstanceOf(CliConnectionException.class, error.getCause());
    }

    @Test
    void clientRequiresConnect() {
        ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults(), new FakeTransport());
        assertThrows(CliConnectionException.class, () -> client.query("hi"));
    }

    @Test
    void clientMultiTurnReceiveResponseStopsAtResult() {
        FakeTransport cli = new FakeTransport().answeringPrompts("pong");
        try (ClaudeSdkClient client = new ClaudeSdkClient(ClaudeAgentOptions.defaults(), cli)) {
            client.connect();
            for (int turn = 0; turn < 2; turn++) {
                client.query("ping " + turn);
                List<Message> messages = drain(client.receiveResponse());
                assertEquals(2, messages.size());
                assertInstanceOf(ResultMessage.class, messages.getLast());
            }
            assertEquals("default", cli.writtenOfType("user").getFirst().get("session_id"));
        }
    }

    @Test
    void hostOnlySessionStateFramesAreHidden() {
        FakeTransport cli = new FakeTransport().onWrite((message, fake) -> {
            if ("user".equals(message.get("type"))) {
                fake.emit(sessionState("running"));
                fake.emit(Map.of("type", "system", "subtype", "session_state_changed", "state", "running"));
                fake.emit(assistant("a"));
                fake.emit(result(false));
                fake.emit(sessionState("idle"));
                fake.finish();
            }
        });
        List<Message> messages = drain(ClaudeAgent.query("x", ClaudeAgentOptions.defaults(), cli));
        assertEquals(3, messages.size(), "only the frame the caller opted into is visible");
        assertEquals("session_state_changed", ((SystemMessage) messages.getFirst()).subtype());
    }

    @Test
    void bidirectionalQueryKeepsStdinOpenUntilIdle() throws Exception {
        FakeTransport cli = new FakeTransport().onWrite((message, fake) -> {
            if ("user".equals(message.get("type"))) {
                fake.emit(sessionState("running"));
                fake.emit(assistant("a"));
                fake.emit(result(false));
            }
        });
        var options = ClaudeAgentOptions.builder().canUseTool((t, i, c) -> PermissionResult.allow()).build();
        try (MessageStream stream = ClaudeAgent.query("x", options, cli)) {
            var iterator = stream.iterator();
            while (!(iterator.next() instanceof ResultMessage)) {
                // read up to the result
            }
            assertFalse(cli.stdinClosed.await(300, TimeUnit.MILLISECONDS), "a background turn may still need stdin");
            cli.emit(sessionState("idle"));
            assertTrue(cli.stdinClosed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonBidirectionalQueryClosesStdinAtResultEvenWhileRunning() throws Exception {
        FakeTransport cli = new FakeTransport().onWrite((message, fake) -> {
            if ("user".equals(message.get("type"))) {
                fake.emit(sessionState("running"));
                fake.emit(result(false));
            }
        });
        try (MessageStream _ = ClaudeAgent.query("x", ClaudeAgentOptions.defaults(), cli)) {
            assertTrue(cli.stdinClosed.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void streamingPromptsAreWrittenInOrder() throws Exception {
        FakeTransport cli = new FakeTransport().answeringPrompts("ok");
        List<Map<String, Object>> prompts = List.of(
                userPrompt("one"), userPrompt("two"));
        try (MessageStream _ = ClaudeAgent.query(prompts, ClaudeAgentOptions.defaults(), cli)) {
            assertTrue(cli.stdinClosed.await(2, TimeUnit.SECONDS));
            List<Map<String, Object>> users = cli.writtenOfType("user");
            assertEquals(2, users.size());
            assertEquals("one", Json.object(users.get(0), "message").get("content"));
            assertEquals("two", Json.object(users.get(1), "message").get("content"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> userPrompt(String text) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", text));
    }

    private static Map<String, Object> mcp(String server, Map<String, Object> message) {
        return Map.of("subtype", "mcp_message", "server_name", server, "message", message);
    }

    private static Map<String, Object> responseOf(FakeTransport cli, String requestId) throws InterruptedException {
        Map<String, Object> written = cli.awaitWritten(m -> "control_response".equals(m.get("type"))
                && requestId.equals(Json.object(m, "response").get("request_id")));
        return Json.object(written, "response");
    }

    private static Map<String, Object> responsePayload(FakeTransport cli, String requestId) throws InterruptedException {
        Map<String, Object> response = responseOf(cli, requestId);
        assertEquals("success", response.get("subtype"), () -> "error: " + response.get("error"));
        return Json.object(response, "response");
    }

    private static Map<String, Object> mcpResponse(FakeTransport cli, String requestId) throws InterruptedException {
        return Json.object(responsePayload(cli, requestId), "mcp_response");
    }

    private static String errorOf(FakeTransport cli, String requestId) throws InterruptedException {
        Map<String, Object> response = responseOf(cli, requestId);
        assertEquals("error", response.get("subtype"));
        return (String) response.get("error");
    }

    private static Map<String, Object> requestOf(FakeTransport cli, String subtype) {
        return cli.writtenOfType("control_request").stream()
                .filter(m -> isControlRequest(m, subtype))
                .map(m -> Json.object(m, "request"))
                .findFirst().orElseThrow();
    }
}
