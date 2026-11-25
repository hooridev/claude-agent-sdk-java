package dev.hoori.agents.claude;

import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Connection;
import dev.hoori.agents.claude.mcp.McpServerConfig;
import dev.hoori.agents.claude.mcp.SdkMcpServer;
import dev.hoori.agents.claude.mcp.SdkMcpTool;
import dev.hoori.agents.claude.transport.Transport;

/**
 * Entry point for one-shot queries (Python: {@code query()}).
 *
 * <pre>{@code
 * try (MessageStream messages = ClaudeAgent.query("What is 2 + 2?", options)) {
 *     for (Message message : messages) {
 *         if (message instanceof AssistantMessage a) System.out.println(a.text());
 *     }
 * }
 * }</pre>
 *
 * <p>For interactive conversations with follow-ups, interrupts and dynamic control use
 * {@link ClaudeSdkClient} instead.
 */
public final class ClaudeAgent {

    private ClaudeAgent() {
    }

    /** Queries with default options. */
    public static MessageStream query(String prompt) {
        return query(prompt, ClaudeAgentOptions.defaults());
    }

    /** Sends one prompt and streams all messages until the run is over. */
    public static MessageStream query(String prompt, ClaudeAgentOptions options) {
        return query(prompt, options, null);
    }

    /**
     * Like {@link #query(String, ClaudeAgentOptions)}, over a custom transport. Command-line options
     * (model, cwd, tools, ...) are not applied to a custom transport.
     */
    public static MessageStream query(String prompt, ClaudeAgentOptions options, Transport transport) {
        Connection connection = Connection.open(options, transport);
        try {
            connection.protocol().sendPromptAndEndInput(Connection.userMessage(prompt, ""));
        } catch (RuntimeException e) {
            connection.close();
            throw e;
        }
        return new MessageStream(connection.protocol().receive(), connection::close, false);
    }

    /**
     * Streams prompts in the wire format ({@code {"type":"user","message":{"role":"user","content":...}}})
     * from {@code prompts}, consumed on a background thread, so its iterator may block.
     */
    public static MessageStream query(Iterable<Map<String, Object>> prompts, ClaudeAgentOptions options) {
        return query(prompts, options, null);
    }

    /** Streaming prompts over a custom transport. */
    public static MessageStream query(Iterable<Map<String, Object>> prompts, ClaudeAgentOptions options,
                                      Transport transport) {
        Connection connection = Connection.open(options, transport);
        connection.protocol().streamInputInBackground(prompts);
        return new MessageStream(connection.protocol().receive(), connection::close, false);
    }

    /**
     * Creates an in-process MCP server configuration (Python: {@code create_sdk_mcp_server}).
     * Add it with {@code ClaudeAgentOptions.builder().mcpServer(name, config)}.
     */
    public static McpServerConfig.Sdk createSdkMcpServer(String name, String version, List<SdkMcpTool> tools) {
        return McpServerConfig.sdk(SdkMcpServer.builder(name).version(version).tools(tools).build());
    }
}
