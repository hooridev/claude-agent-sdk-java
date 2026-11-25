package dev.hoori.agents.claude.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.transport.SubprocessCliTransport;
import dev.hoori.agents.claude.transport.Transport;

/**
 * A connected, initialized CLI session: transport plus control protocol (Template for the
 * connect sequence shared by {@code ClaudeAgent.query} and {@code ClaudeSdkClient.connect}).
 */
public final class Connection implements AutoCloseable {

    private final ClaudeAgentOptions options;
    private final Transport transport;
    private final ControlProtocol protocol;
    private final SessionStoreSupport.Prepared sessionStore;

    private Connection(ClaudeAgentOptions options, Transport transport, ControlProtocol protocol,
                       SessionStoreSupport.Prepared sessionStore) {
        this.options = options;
        this.transport = transport;
        this.protocol = protocol;
        this.sessionStore = sessionStore;
    }

    /**
     * Validates the options, starts the transport (the subprocess unless {@code customTransport}
     * is given) and runs the initialize handshake. On failure everything started is cleaned up.
     */
    public static Connection open(ClaudeAgentOptions options, Transport customTransport) {
        SessionStoreSupport.validate(options);
        // A custom transport never sees the materialized options, so store-backed resume is skipped for it.
        SessionStoreSupport.Prepared prepared = SessionStoreSupport.prepare(options, customTransport == null);
        Transport transport = null;
        ControlProtocol protocol = null;
        try {
            ClaudeAgentOptions configured = prepared.apply(CanUseToolConfigurer.configure(options));
            transport = customTransport != null ? customTransport : new SubprocessCliTransport(configured);
            transport.connect();
            protocol = new ControlProtocol(transport, ProtocolSettings.from(configured));
            prepared.attachMirror(protocol, configured);
            protocol.start();
            protocol.initialize();
            return new Connection(configured, transport, protocol, prepared);
        } catch (RuntimeException e) {
            try {
                if (protocol != null) {
                    protocol.close();
                } else if (transport != null) {
                    transport.close();
                }
            } finally {
                prepared.cleanup();
            }
            throw e;
        }
    }

    /** A user message in the wire format. */
    public static Map<String, Object> userMessage(String prompt, String sessionId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "user");
        message.put("session_id", sessionId);
        message.put("message", Map.of("role", "user", "content", prompt));
        message.put("parent_tool_use_id", null);
        return message;
    }

    public ClaudeAgentOptions options() {
        return options;
    }

    public Transport transport() {
        return transport;
    }

    public ControlProtocol protocol() {
        return protocol;
    }

    /** Terminates the CLI, then removes any temporary resume directory (in that order). */
    @Override
    public void close() {
        try {
            protocol.close();
        } finally {
            sessionStore.cleanup();
        }
    }
}
