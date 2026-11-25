package dev.hoori.agents.claude.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import dev.hoori.agents.claude.mcp.McpServer;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.mcp.SdkMcpTool;
import dev.hoori.agents.claude.mcp.ToolResult;

@Timeout(10)
class McpMessageHandlerTest {

    @Test
    void cancellationInterruptsOnlyThisConnectionsCall() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        SdkMcpServer shared = SdkMcpServer.builder("s").tool(SdkMcpTool.builder("slow", "d").handler(a -> {
            started.countDown();
            Thread.sleep(1_000);
            return ToolResult.text("done");
        }).build()).build();
        Map<String, McpServer> servers = Map.of("s", shared);
        McpMessageHandler first = new McpMessageHandler(servers);
        McpMessageHandler second = new McpMessageHandler(servers);
        Map<String, Object> call = Map.of("subtype", "mcp_message", "server_name", "s", "message",
                Map.of("jsonrpc", "2.0", "id", 7, "method", "tools/call", "params", Map.of("name", "slow")));
        AtomicReference<Map<String, Object>> firstResponse = new AtomicReference<>();
        AtomicReference<Map<String, Object>> secondResponse = new AtomicReference<>();
        first.onReceived(call);
        second.onReceived(call);
        Thread a = Thread.ofVirtual().start(() -> firstResponse.set(first.handle(call)));
        Thread b = Thread.ofVirtual().start(() -> secondResponse.set(second.handle(call)));
        assertTrue(started.await(2, TimeUnit.SECONDS));

        first.onReceived(cancel(7L));
        a.join(3000);
        b.join(3000);

        Map<String, Object> cancelled = Json.object(firstResponse.get(), "mcp_response");
        assertEquals(-32800, ((Number) Json.object(cancelled, "error").get("code")).intValue());
        Map<String, Object> other = Json.object(Json.object(secondResponse.get(), "mcp_response"), "result");
        assertEquals(false, other.get("isError"), "the same id on another connection keeps running");
    }

    @Test
    void cancellationBeforeTheWorkerStartsSkipsTheTool() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        McpMessageHandler handler = new McpMessageHandler(Map.of("s", SdkMcpServer.builder("s")
                .tool(SdkMcpTool.builder("t", "d").handler(a -> {
                    calls.incrementAndGet();
                    return ToolResult.text("ran");
                }).build()).build()));
        Map<String, Object> call = Map.of("subtype", "mcp_message", "server_name", "s", "message",
                Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", "t")));
        handler.onReceived(call);
        handler.onReceived(cancel(1));
        Map<String, Object> response = Json.object(handler.handle(call), "mcp_response");
        assertEquals(-32800, ((Number) Json.object(response, "error").get("code")).intValue());
        assertEquals(0, calls.get());
    }

    private static Map<String, Object> cancel(Object requestId) {
        return Map.of("subtype", "mcp_message", "server_name", "s", "message", Map.of("jsonrpc", "2.0",
                "method", "notifications/cancelled", "params", Map.of("requestId", requestId)));
    }
}
