# Claude Agent SDK for Java (unofficial)

> [!WARNING]
> **This is NOT an official Anthropic product and NOT an official port.**
> It is an independent, community-made Java port of the
> [Claude Agent SDK for Python](https://github.com/anthropics/claude-agent-sdk-python).
> The port is **not exactly identical** to the Python SDK: it tries to reproduce its
> functionality as closely as possible, but behaviour, API shape and feature coverage can
> differ, lag behind upstream releases, or contain bugs that the original does not have.
> Do not report problems with this library to Anthropic.

The SDK lets Java applications drive [Claude Code](https://docs.claude.com/en/docs/claude-code)
as an agent: one-shot queries, interactive multi-turn sessions, in-process tools (MCP),
hooks, permission callbacks, subagents, structured output and session management.

## Requirements

- **Java 25** or newer
- **An installed Claude Code CLI.** This library does **not** ship the CLI (for copyright
  reasons) and never downloads it. Install it yourself and log in:
  ```bash
  curl -fsSL https://claude.ai/install.sh | bash     # or: npm install -g @anthropic-ai/claude-code
  claude   # log in once
  ```
  The SDK finds `claude` on the `PATH` and in the usual install locations
  (`~/.local/bin`, `~/.npm-global/bin`, `/usr/local/bin`, `~/.claude/local`, ...), or you
  point it at a binary with `ClaudeAgentOptions.builder().cliPath("/path/to/claude")`.
- The only runtime dependency is Jackson 3 (`tools.jackson.core:jackson-databind`).

## Installation

```xml
<dependency>
    <groupId>dev.hoori.agents</groupId>
    <artifactId>claude-agent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Quick start

```java
import dev.hoori.agents.claude.*;
import dev.hoori.agents.claude.message.*;

try (MessageStream messages = ClaudeAgent.query("What is 2 + 2?")) {
    for (Message message : messages) {
        if (message instanceof AssistantMessage assistant) {
            System.out.println(assistant.text());
        }
    }
}
```

The API is **blocking** and built for virtual threads: `MessageStream` is an `Iterable` (and
offers `stream()`), callbacks are plain functional interfaces that run on virtual threads.
Messages are sealed types, so `switch` with pattern matching works well:

```java
switch (message) {
    case AssistantMessage a -> System.out.println(a.text());
    case ResultMessage r -> System.out.printf("cost: $%.4f%n", r.totalCostUsd());
    case SystemMessage s when s.subtype().equals("init") -> System.out.println(s.data().get("tools"));
    default -> { }
}
```

## Options

`ClaudeAgentOptions` is immutable and built with a builder (`toBuilder()` derives variants):

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .model("claude-haiku-5-5")
        .effort(EffortLevel.LOW)
        .systemPrompt(SystemPrompt.claudeCode("Always answer in German."))
        .allowedTools("Read", "Write", "Bash(git status:*)")
        .permissionMode(PermissionMode.ACCEPT_EDITS)
        .cwd("/path/to/project")
        .maxTurns(5)
        .build();
```

As in the Python SDK, not setting a system prompt means an **empty** system prompt; use
`SystemPrompt.claudeCode()` for Claude Code's own prompt.

## Interactive client

```java
try (ClaudeSdkClient client = new ClaudeSdkClient(options)) {
    client.connect();
    client.query("What's the capital of France?");
    for (Message m : client.receiveResponse()) { /* ... until the ResultMessage */ }

    client.query("And its population?");            // same conversation
    for (Message m : client.receiveResponse()) { /* ... */ }

    client.interrupt();                              // from any thread
    client.setPermissionMode(PermissionMode.PLAN);
    client.setModel("claude-sonnet-5-5");
    Map<String, Object> mcp = client.getMcpStatus();
}
```

## In-process tools (SDK MCP servers)

```java
SdkMcpTool add = SdkMcpTool.builder("add", "Add two numbers")
        .param("a", "number").param("b", "number")
        .handler(args -> ToolResult.text(String.valueOf(
                ((Number) args.get("a")).doubleValue() + ((Number) args.get("b")).doubleValue())))
        .build();

ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .mcpServer("calc", McpServerConfig.sdk(SdkMcpServer.builder("calc").tool(add).build()))
        .allowedTools("mcp__calc__add")
        .build();
```

External servers work too: `McpServerConfig.stdio(...)`, `.sse(url)`, `.http(url)`. Implement
`McpServer` yourself to serve resources or prompts in-process.

## Hooks and permission callbacks

```java
ClaudeAgentOptions options = ClaudeAgentOptions.builder()
        .hook(HookEvent.PRE_TOOL_USE, HookMatcher.of("Bash", (input, toolUseId, ctx) ->
                String.valueOf(input.toolInput().get("command")).contains("rm -rf")
                        ? HookOutput.builder().hookSpecificOutput(
                                HookSpecificOutput.preToolUse("deny", "Not allowed")).build()
                        : HookOutput.empty()))
        .canUseTool((toolName, input, context) -> toolName.equals("Write")
                ? PermissionResult.deny("read-only session")
                : PermissionResult.allow())
        .build();
```

## Sessions

Stored sessions (the JSONL transcripts under `~/.claude/projects`) can be listed, read,
renamed, tagged, forked and deleted, and mirrored to your own storage via a `SessionStore`
(package `dev.hoori.agents.claude.session`):

```java
List<SessionInfo> sessions = ClaudeSessions.listSessions("/path/to/project");
List<SessionMessage> messages = ClaudeSessions.getSessionMessages(sessions.getFirst().sessionId());
SessionMutations.renameSession(sessionId, "Refactoring notes");
ForkSessionResult fork = SessionMutations.forkSession(sessionId);

// Mirror transcripts to your own storage and resume from it:
SessionStore store = new InMemorySessionStore();     // or your adapter (S3, Postgres, ...)
ClaudeAgentOptions options = ClaudeAgentOptions.builder().sessionStore(store).build();
SessionStoreConformance.run(MyStore::new);           // verify your own adapter
```

Every read/mutation also exists as a `...FromStore` / `...ViaStore` variant working on a
`SessionStore`, and `SessionMutations.importSessionToStore(...)` copies a local session into one.

## Errors

All exceptions are unchecked and extend `ClaudeSdkException`:
`CliNotFoundException`, `CliConnectionException`, `ProcessException`, `ResultException`
(the CLI ended the run with an error result; carries `subtype()`, `errors()`,
`terminalReason()`, ...), `CliJsonDecodeException`, `MessageParseException`.

## Examples

See [`examples/`](examples). Run one with the JDK source launcher, e.g.:

```bash
mvn -q package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp target/classes:$(cat target/cp.txt) examples/QuickStart.java
```

## Differences to the Python SDK

The port follows the Python SDK closely (same CLI flags, control protocol, defaults and
validation), but it is Java, so some things are different or missing:

- **Blocking API** instead of `async`/`await`; background work runs on virtual threads.
- **Builders, records and sealed types** instead of dataclasses/`TypedDict`s. Free-form JSON
  (tool input, usage, hook input, ...) is `Map<String, Object>`.
- Hook output uses the CLI's field names directly (`continue`, `async`); no `continue_`.
- **No bundled CLI**; a `claude` executable must be installed.
- **In-process MCP** is implemented directly on JSON-RPC (`initialize`, `ping`, `tools/list`,
  `tools/call`, cancellation) without the MCP SDK. Tool input validation supports a subset of
  JSON Schema (`type`, `properties`, `required`, `items`, `enum`, `anyOf`).
- Tool input schemas are declared with `param(...)` or a JSON-schema map; there is no
  derivation from types (Python derives them from type hints / `TypedDict`s).
- Not supported: running the CLI as another OS user (`user`), OpenTelemetry trace-context
  propagation, deriving a client-app label automatically (set `clientApp(...)` yourself),
  the deprecated `debug_stderr`.
- The message queue between the reader and the consumer is unbounded.
- `CLAUDE_CODE_ENTRYPOINT` is reported as `sdk-java`.

## Known limitations

Found in review and accepted as rare:

- If a `SessionStore.append` hangs longer than its 60 s timeout and then still commits, it can
  land after a later batch of the same session (same as the Python SDK, which does not retry timeouts).
- If the JVM exits exactly while a store-backed resume is being materialized, the temporary
  config directory (owner-only, contains a copy of the OAuth credentials) may be left behind.
- A relative `CLAUDE_CONFIG_DIR` is resolved against the JVM's working directory, not `cwd`
  (same as the Python SDK).
- Reusing one `Transport` instance (passed to `ClaudeSdkClient`/`ClaudeAgent.query`) for a new
  connection while background work of the old connection is still running (a blocking prompt
  iterator, a stdout reader, an initial prompt write) is not supported: that old work can act on the
  new connection. Use a fresh transport per connection (the default subprocess transport does this).

## Building and testing

```bash
mvn verify                    # unit tests (no CLI needed)
mvn -Pintegration test        # also runs tests against the installed claude CLI (cheap Haiku model)
```

Publishing to Maven Central uses the `release` profile (sources, javadoc, GPG signing,
`central-publishing-maven-plugin`): `mvn -Prelease deploy`.

## License

MIT, see [LICENSE](LICENSE). Derived from the MIT-licensed Claude Agent SDK for Python,
Copyright (c) 2025 Anthropic, PBC. "Claude" and "Claude Code" are trademarks of Anthropic.
