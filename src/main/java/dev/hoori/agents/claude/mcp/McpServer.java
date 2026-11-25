package dev.hoori.agents.claude.mcp;

import java.util.Map;

/**
 * An in-process MCP server, served to Claude Code over the control protocol.
 *
 * <p>{@link SdkMcpServer} covers tools; implement this interface yourself to serve other MCP
 * features (resources, prompts, ...). Calls arrive concurrently on virtual threads; one instance may serve several connections at
 * once. A {@code notifications/cancelled} for a running request interrupts its thread.
 */
public interface McpServer {

    /** The server name. */
    String name();

    /**
     * Handles one JSON-RPC message from Claude Code.
     *
     * @return the JSON-RPC response for requests, or {@code null} for notifications and responses
     * @throws Exception answered with a JSON-RPC internal error
     */
    Map<String, Object> handle(Map<String, Object> message) throws Exception;
}
