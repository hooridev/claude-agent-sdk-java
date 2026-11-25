package dev.hoori.agents.claude.mcp;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.JsonSchemaValidator;

/**
 * An in-process MCP server exposing {@link SdkMcpTool}s (Python: {@code create_sdk_mcp_server}).
 *
 * <pre>{@code
 * SdkMcpServer calculator = SdkMcpServer.builder("calculator").tool(add).build();
 * var options = ClaudeAgentOptions.builder()
 *         .mcpServer("calc", McpServerConfig.sdk(calculator))
 *         .allowedTools("mcp__calc__add")
 *         .build();
 * }</pre>
 *
 * <p>Implements the MCP methods {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}, a cancelled call is interrupted (its handler sees {@link Thread#isInterrupted()}).
 */
public final class SdkMcpServer implements McpServer {

    private static final System.Logger LOG = System.getLogger(SdkMcpServer.class.getName());
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int REQUEST_CANCELLED = -32800;

    private final String name;
    private final String version;
    private final Map<String, SdkMcpTool> tools;

    private SdkMcpServer(String name, String version, List<SdkMcpTool> tools) {
        this.name = Objects.requireNonNull(name, "name");
        this.version = version;
        Map<String, SdkMcpTool> byName = new LinkedHashMap<>();
        for (SdkMcpTool tool : tools) {
            byName.put(tool.name(), tool);
        }
        this.tools = java.util.Collections.unmodifiableMap(byName);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    @Override
    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    /** The tools in registration order. */
    public List<SdkMcpTool> tools() {
        return List.copyOf(tools.values());
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> message) {
        String method = Json.string(message, "method");
        Object id = message.get("id");
        if (method == null) {
            return null; // a response to a server->client request; this server sends none
        }
        if (!message.containsKey("id")) {
            return null; // notifications: cancellation is handled per connection by the SDK
        }
        Map<String, Object> params = Json.object(message, "params");
        return switch (method) {
            case "initialize" -> success(id, initializeResult(params));
            case "ping" -> success(id, Map.of());
            case "tools/list" -> success(id, Map.of("tools", tools().stream().map(SdkMcpTool::toWire).toList()));
            case "tools/call" -> callTool(id, params);
            default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
        };
    }

    private Map<String, Object> initializeResult(Map<String, Object> params) {
        String requested = Json.string(params, "protocolVersion");
        return Json.obj(
                "protocolVersion", requested != null ? requested : DEFAULT_PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Json.obj("name", name, "version", version));
    }

    private Map<String, Object> callTool(Object id, Map<String, Object> params) {
        String toolName = Json.string(params, "name");
        if (toolName == null) {
            return error(id, INVALID_PARAMS, "tools/call requires a tool name");
        }
        Map<String, Object> arguments = Json.object(params, "arguments");
        ToolResult result = runTool(toolName, arguments == null ? new LinkedHashMap<>() : arguments);
        if (Thread.interrupted()) {
            return error(id, REQUEST_CANCELLED, "Request cancelled");
        }
        return success(id, Json.obj("content", convertContent(result.content()), "isError", result.isError()));
    }

    private ToolResult runTool(String toolName, Map<String, Object> arguments) {
        SdkMcpTool tool = tools.get(toolName);
        if (tool == null) {
            return ToolResult.error("Tool '" + toolName + "' not found");
        }
        String validationError = JsonSchemaValidator.validate(arguments, tool.inputSchema());
        if (validationError != null) {
            return ToolResult.error("Input validation error: " + validationError);
        }
        try {
            ToolResult result = tool.handler().call(arguments);
            return result == null ? new ToolResult(List.of(), false) : result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Tool call cancelled");
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** Converts tool content to what Claude Code accepts (mirrors Python's {@code _convert_tool_content}). */
    private static List<Map<String, Object>> convertContent(List<Map<String, Object>> items) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String type = Json.string(item, "type");
            switch (type == null ? "" : type) {
                case "text" -> content.add(Json.obj("type", "text", "text", item.get("text")));
                case "image" -> content.add(Json.obj("type", "image", "data", item.get("data"),
                        "mimeType", item.get("mimeType")));
                case "resource_link" -> {
                    List<String> parts = new ArrayList<>();
                    for (String part : List.of("name", "uri", "description")) {
                        if (item.get(part) instanceof String s && !s.isEmpty()) {
                            parts.add(s);
                        }
                    }
                    content.add(Json.obj("type", "text", "text",
                            parts.isEmpty() ? "Resource link" : String.join("\n", parts)));
                }
                case "resource" -> {
                    Map<String, Object> resource = Json.object(item, "resource");
                    if (resource != null && resource.containsKey("text")) {
                        content.add(Json.obj("type", "text", "text", resource.get("text")));
                    } else {
                        LOG.log(Level.WARNING, "Binary embedded resource cannot be converted to text, skipping");
                    }
                }
                default -> LOG.log(Level.WARNING, "Unsupported content type {0} in tool result, skipping", type);
            }
        }
        return content;
    }

    private static Map<String, Object> success(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    /** Builder for {@link SdkMcpServer}. */
    public static final class Builder {
        private final String name;
        private String version = "1.0.0";
        private final List<SdkMcpTool> tools = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder tool(SdkMcpTool tool) {
            tools.add(tool);
            return this;
        }

        public Builder tools(List<SdkMcpTool> tools) {
            this.tools.addAll(tools);
            return this;
        }

        public SdkMcpServer build() {
            return new SdkMcpServer(name, version, tools);
        }
    }
}
