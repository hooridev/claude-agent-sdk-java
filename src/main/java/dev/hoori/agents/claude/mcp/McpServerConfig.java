package dev.hoori.agents.claude.mcp;

import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/** Configuration of an MCP server made available to Claude. */
public sealed interface McpServerConfig {

    /** The configuration as passed to the CLI in {@code --mcp-config}. */
    Map<String, Object> toCliConfig();

    static Stdio stdio(String command, String... args) {
        return new Stdio(command, List.of(args), Map.of());
    }

    static Sse sse(String url) {
        return new Sse(url, Map.of());
    }

    static Http http(String url) {
        return new Http(url, Map.of());
    }

    /** An in-process server, e.g. one built with {@link SdkMcpServer#builder(String)}. */
    static Sdk sdk(McpServer server) {
        return new Sdk(server.name(), server);
    }

    /** A server the CLI starts as a subprocess. */
    record Stdio(String command, List<String> args, Map<String, String> env) implements McpServerConfig {
        @Override
        public Map<String, Object> toCliConfig() {
            return Json.obj("type", "stdio", "command", command, "args", args.isEmpty() ? null : args,
                    "env", env.isEmpty() ? null : env);
        }

        public Stdio withEnv(Map<String, String> env) {
            return new Stdio(command, args, Map.copyOf(env));
        }
    }

    /** A remote server over server-sent events. */
    record Sse(String url, Map<String, String> headers) implements McpServerConfig {
        @Override
        public Map<String, Object> toCliConfig() {
            return Json.obj("type", "sse", "url", url, "headers", headers.isEmpty() ? null : headers);
        }

        public Sse withHeaders(Map<String, String> headers) {
            return new Sse(url, Map.copyOf(headers));
        }
    }

    /** A remote server over streamable HTTP. */
    record Http(String url, Map<String, String> headers) implements McpServerConfig {
        @Override
        public Map<String, Object> toCliConfig() {
            return Json.obj("type", "http", "url", url, "headers", headers.isEmpty() ? null : headers);
        }

        public Http withHeaders(Map<String, String> headers) {
            return new Http(url, Map.copyOf(headers));
        }
    }

    /** An in-process server; the CLI sees only {@code type} and {@code name}. */
    record Sdk(String name, McpServer instance) implements McpServerConfig {
        @Override
        public Map<String, Object> toCliConfig() {
            return Json.obj("type", "sdk", "name", name);
        }
    }
}
