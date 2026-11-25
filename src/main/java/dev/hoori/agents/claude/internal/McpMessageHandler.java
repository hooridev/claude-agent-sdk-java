package dev.hoori.agents.claude.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.hoori.agents.claude.mcp.McpServer;

/** Routes {@code mcp_message} requests to the in-process MCP server they name. */
final class McpMessageHandler implements ControlRequestHandler {

    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;
    private static final int REQUEST_CANCELLED = -32800;

    private final Map<String, McpServer> servers;
    /** This connection's MCP requests, keyed by server and JSON-RPC id, registered in reader order. */
    private final Map<String, Call> calls = new ConcurrentHashMap<>();

    /** A request's worker thread and whether a cancellation arrived (both volatile: either side sees the other). */
    private static final class Call {
        volatile Thread thread;
        volatile boolean cancelled;
    }

    McpMessageHandler(Map<String, McpServer> servers) {
        this.servers = servers;
    }

    @Override
    public String subtype() {
        return "mcp_message";
    }

    /** Runs on the reader thread, so a cancellation always finds the request it follows. */
    @Override
    public void onReceived(Map<String, Object> request) {
        String serverName = Json.string(request, "server_name");
        Map<String, Object> message = Json.object(request, "message");
        if (serverName == null || message == null) {
            return;
        }
        if ("notifications/cancelled".equals(message.get("method"))) {
            Map<String, Object> params = Json.object(message, "params");
            Call call = params == null ? null : calls.get(key(serverName, params.get("requestId")));
            if (call != null) {
                call.cancelled = true;
                Thread thread = call.thread;
                if (thread != null) {
                    thread.interrupt();
                }
            }
        } else if (message.containsKey("id") && message.get("method") != null && servers.containsKey(serverName)) {
            calls.put(key(serverName, message.get("id")), new Call());
        }
    }

    @Override
    public Map<String, Object> handle(Map<String, Object> request) {
        String serverName = Json.string(request, "server_name");
        Map<String, Object> message = Json.object(request, "message");
        if (serverName == null || message == null) {
            throw new IllegalArgumentException("Missing server_name or message for MCP request");
        }
        Map<String, Object> response = route(serverName, message);
        // JSON-RPC notifications get no reply, but the control request that carried one still expects an ack.
        return Map.of("mcp_response", response != null ? response : Map.of("jsonrpc", "2.0", "result", Map.of()));
    }

    private Map<String, Object> route(String serverName, Map<String, Object> message) {
        McpServer server = servers.get(serverName);
        if (server == null) {
            return error(message.get("id"), METHOD_NOT_FOUND, "Server '" + serverName + "' not found");
        }
        String key = message.containsKey("id") && message.get("method") != null
                ? key(serverName, message.get("id")) : null;
        Call call = key == null ? null : calls.computeIfAbsent(key, k -> new Call());
        try {
            if (call != null) {
                call.thread = Thread.currentThread();
                if (call.cancelled) {
                    throw new InterruptedException(); // cancelled before it started
                }
            }
            return server.handle(message);
        } catch (InterruptedException e) {
            return error(message.get("id"), REQUEST_CANCELLED, "Request cancelled");
        } catch (Exception e) {
            String text = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return error(message.get("id"), INTERNAL_ERROR, text);
        } finally {
            if (key != null) {
                calls.remove(key);
            }
        }
    }

    /** JSON numbers may arrive as Integer or Long: compare ids by their JSON form. */
    private static String key(String serverName, Object id) {
        return serverName + "\u0000" + Json.write(id);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
