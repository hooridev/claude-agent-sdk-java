package dev.hoori.agents.claude;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.internal.Connection;
import dev.hoori.agents.claude.internal.ControlProtocol;
import dev.hoori.agents.claude.options.PermissionMode;
import dev.hoori.agents.claude.transport.Transport;

/**
 * Interactive, bidirectional conversations with Claude Code (Python: {@code ClaudeSDKClient}).
 *
 * <pre>{@code
 * try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
 *     client.connect();
 *     client.query("Hello!");
 *     for (Message m : client.receiveResponse()) { ... }
 *     client.query("And now?");
 *     for (Message m : client.receiveResponse()) { ... }
 * }
 * }</pre>
 *
 * <p>Sending ({@link #query}) and receiving may happen on different threads. A client is bound to
 * one CLI process; {@link #close()} (or {@link #disconnect()}) ends it.
 */
public final class ClaudeSdkClient implements AutoCloseable {

    private final ClaudeAgentOptions options;
    private final Transport customTransport;
    private volatile Connection connection;
    /** A connection whose initial prompt is still being written: closable by disconnect(), not yet usable. */
    private Connection opening;

    public ClaudeSdkClient() {
        this(ClaudeAgentOptions.defaults());
    }

    public ClaudeSdkClient(ClaudeAgentOptions options) {
        this(options, null);
    }

    /** A client over a custom transport (CLI options are not applied to it). */
    public ClaudeSdkClient(ClaudeAgentOptions options, Transport transport) {
        this.options = options == null ? ClaudeAgentOptions.defaults() : options;
        this.customTransport = transport;
    }

    /** Connects without an initial prompt. */
    public ClaudeSdkClient connect() {
        return connectWith(null);
    }

    /** Connects and sends an initial prompt. */
    public ClaudeSdkClient connect(String prompt) {
        return connectWith(protocol -> protocol.writeUserMessage(Connection.userMessage(prompt, "default")));
    }

    /** Connects and streams prompts (wire format) from {@code prompts} on a background thread. */
    public ClaudeSdkClient connect(Iterable<Map<String, Object>> prompts) {
        return connectWith(protocol -> protocol.streamInputInBackground(prompts));
    }

    private ClaudeSdkClient connectWith(java.util.function.Consumer<ControlProtocol> initialInput) {
        Connection opened;
        synchronized (this) {
            if (connection != null || opening != null) {
                throw new IllegalStateException("Already connected");
            }
            opened = Connection.open(options, customTransport);
            opening = opened;
        }
        // Outside the monitor: a write stuck on a full pipe must not block disconnect() from another thread.
        try {
            if (initialInput != null) {
                initialInput.accept(opened.protocol());
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                if (opening == opened) {
                    opening = null;
                }
            }
            opened.close();
            throw e;
        }
        synchronized (this) {
            if (opening != opened) {
                throw new CliConnectionException("Disconnected while connecting");
            }
            opening = null;
            connection = opened; // published only after the initial prompt, so follow-ups come after it
        }
        return this;
    }

    private ControlProtocol protocol() {
        Connection current = connection;
        if (current == null) {
            throw new CliConnectionException("Not connected. Call connect() first.");
        }
        return current.protocol();
    }

    /** Sends a new prompt in the {@code default} session. */
    public void query(String prompt) {
        query(prompt, "default");
    }

    /** Sends a new prompt in the given session. */
    public void query(String prompt, String sessionId) {
        protocol().writeUserMessage(Connection.userMessage(prompt, sessionId));
    }

    /** Sends messages in the wire format; {@code session_id} defaults to {@code sessionId}. */
    public void query(Iterable<Map<String, Object>> messages, String sessionId) {
        ControlProtocol protocol = protocol();
        for (Map<String, Object> message : messages) {
            Map<String, Object> copy = new LinkedHashMap<>(message);
            copy.putIfAbsent("session_id", sessionId);
            protocol.writeUserMessage(copy);
        }
    }

    /** All messages until the connection ends. Closing the returned stream does not disconnect. */
    public MessageStream receiveMessages() {
        return new MessageStream(protocol().receive(), () -> {
        }, false);
    }

    /**
     * Messages up to and including the next {@code ResultMessage}. Note that with injected turns
     * (background tasks) that result may belong to another turn; see {@code ResultMessage.userMessageUuid}.
     */
    public MessageStream receiveResponse() {
        return new MessageStream(protocol().receive(), () -> {
        }, true);
    }

    /** Interrupts the current turn. */
    public void interrupt() {
        protocol().interrupt();
    }

    /** Changes the permission mode of the session. */
    public void setPermissionMode(PermissionMode mode) {
        protocol().setPermissionMode(mode);
    }

    /** Changes the model; {@code null} resets to the default. */
    public void setModel(String model) {
        protocol().setModel(model);
    }

    /** Rewinds tracked files to their state at a user message (needs {@code enableFileCheckpointing}). */
    public void rewindFiles(String userMessageId) {
        protocol().rewindFiles(userMessageId);
    }

    /** Reconnects a disconnected or failed MCP server. */
    public void reconnectMcpServer(String serverName) {
        protocol().reconnectMcpServer(serverName);
    }

    /** Enables or disables an MCP server. */
    public void toggleMcpServer(String serverName, boolean enabled) {
        protocol().toggleMcpServer(serverName, enabled);
    }

    /** Stops a running background task (id from a {@code TaskStartedMessage}). */
    public void stopTask(String taskId) {
        protocol().stopTask(taskId);
    }

    /** MCP server connection status ({@code mcpServers} list with name, status, tools, ...). */
    public Map<String, Object> getMcpStatus() {
        return protocol().mcpStatus();
    }

    /** Breakdown of the current context window usage by category. */
    public Map<String, Object> getContextUsage() {
        return protocol().contextUsage();
    }

    /** The CLI's initialize response (commands, output styles, ...). */
    public Map<String, Object> getServerInfo() {
        return protocol().initializationResult();
    }

    /** Whether {@link #connect()} succeeded and the client was not disconnected. */
    public boolean isConnected() {
        return connection != null;
    }

    /** Ends the CLI process. The client can be connected again afterwards. */
    public void disconnect() {
        Connection current;
        Connection pending;
        synchronized (this) {
            current = connection;
            pending = opening;
            connection = null;
            opening = null;
        }
        for (Connection c : new Connection[] {current, pending}) {
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }
}
