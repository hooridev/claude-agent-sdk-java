package dev.hoori.agents.claude.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.internal.Json;

class SdkMcpServerTest {

    private static Map<String, Object> request(Object id, String method, Map<String, Object> params) {
        return Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    @Test
    void initializeEchoesProtocolVersionAndAnnotationsAreListed() {
        SdkMcpServer server = SdkMcpServer.builder("s").version("2.0").tool(SdkMcpTool.builder("t", "d")
                .annotations(new ToolAnnotations("T", true, null, null, null, 5000))
                .handler(a -> ToolResult.text("x")).build()).build();
        Map<String, Object> init = Json.object(server.handle(request(1, "initialize",
                Map.of("protocolVersion", "2025-11-25"))), "result");
        assertEquals("2025-11-25", init.get("protocolVersion"));
        assertEquals(Map.of("name", "s", "version", "2.0"), init.get("serverInfo"));

        Map<String, Object> tool = Json.objectList(Json.object(server.handle(request(2, "tools/list", null)), "result"),
                "tools").getFirst();
        assertEquals(Map.of("title", "T", "readOnlyHint", true, "maxResultSizeChars", 5000), tool.get("annotations"));
        assertEquals(Map.of("anthropic/maxResultSizeChars", 5000), tool.get("_meta"));
        assertEquals(Map.of("type", "object", "properties", Map.of(), "required", List.of()), tool.get("inputSchema"));
        assertNull(server.handle(Map.of("jsonrpc", "2.0", "method", "notifications/initialized")));
        assertEquals(-32601, ((Number) Json.object(server.handle(request(3, "resources/list", null)), "error")
                .get("code")).intValue());
    }

    @Test
    void toolErrorsBecomeErrorResultsAndContentIsConverted() {
        SdkMcpServer server = SdkMcpServer.builder("s")
                .tool(SdkMcpTool.builder("fail", "d").handler(a -> {
                    throw new IllegalStateException("bad input");
                }).build())
                .tool(SdkMcpTool.builder("mixed", "d").handler(a -> new ToolResult(List.of(
                        Map.of("type", "resource_link", "name", "doc", "uri", "file:///x"),
                        Map.of("type", "resource", "resource", Map.of("text", "inline")),
                        Map.of("type", "audio", "data", "zz")), false)).build())
                .build();
        Map<String, Object> failed = Json.object(server.handle(request(1, "tools/call", Map.of("name", "fail"))), "result");
        assertEquals(true, failed.get("isError"));
        assertEquals("bad input", Json.objectList(failed, "content").getFirst().get("text"));
        Map<String, Object> missing = Json.object(server.handle(request(2, "tools/call", Map.of("name", "nope"))), "result");
        assertEquals("Tool 'nope' not found", Json.objectList(missing, "content").getFirst().get("text"));
        List<Map<String, Object>> content = Json.objectList(Json.object(
                server.handle(request(3, "tools/call", Map.of("name", "mixed"))), "result"), "content");
        assertEquals(List.of(Map.of("type", "text", "text", "doc\nfile:///x"), Map.of("type", "text", "text", "inline")),
                content);
    }

}
