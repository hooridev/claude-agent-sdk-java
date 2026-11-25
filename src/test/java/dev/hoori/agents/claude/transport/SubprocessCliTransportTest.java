package dev.hoori.agents.claude.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import dev.hoori.agents.claude.ClaudeAgent;
import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.MessageStream;
import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.exception.CliJsonDecodeException;
import dev.hoori.agents.claude.exception.CliNotFoundException;
import dev.hoori.agents.claude.exception.ProcessException;
import dev.hoori.agents.claude.message.AssistantMessage;
import dev.hoori.agents.claude.message.Message;
import dev.hoori.agents.claude.message.ResultMessage;

/** Runs the real subprocess transport against a scripted fake {@code claude}. */
@DisabledOnOs(OS.WINDOWS)
@Timeout(30)
class SubprocessCliTransportTest {

    private static final String SCRIPT = """
            #!/usr/bin/env bash
            printf '%s\\n' "$@" > "$FAKE_DIR/args"
            env > "$FAKE_DIR/env"
            echo "[SandboxDebug] not json"
            echo "diagnostic line" >&2
            while IFS= read -r line; do
              case "$line" in
                *'"subtype":"initialize"'*)
                  id=$(printf '%s' "$line" | grep -o '"request_id":"[^"]*"' | head -1 | cut -d'"' -f4)
                  echo "{\\"type\\":\\"control_response\\",\\"response\\":{\\"subtype\\":\\"success\\",\\"request_id\\":\\"$id\\",\\"response\\":{}}}"
                  ;;
                *'"type":"user"'*)
                  big=$(head -c "${FAKE_TEXT_SIZE:-10}" /dev/zero | tr '\\0' 'a')
                  echo "{\\"type\\":\\"assistant\\",\\"message\\":{\\"model\\":\\"fake\\",\\"content\\":[{\\"type\\":\\"text\\",\\"text\\":\\"$big\\"}]}}"
                  echo '{"type":"result","subtype":"success","duration_ms":1,"duration_api_ms":1,"is_error":false,"num_turns":1,"session_id":"s"}'
                  exit "${FAKE_EXIT:-0}"
                  ;;
              esac
            done
            """;

    @TempDir
    Path dir;

    private Path script() throws Exception {
        Path script = dir.resolve("claude");
        Files.writeString(script, SCRIPT);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private ClaudeAgentOptions.Builder options() throws Exception {
        return ClaudeAgentOptions.builder().cliPath(script()).env("FAKE_DIR", dir.toString()).model("fake-model");
    }

    private static List<Message> drain(MessageStream stream) {
        List<Message> messages = new ArrayList<>();
        stream.forEach(messages::add);
        return messages;
    }

    @Test
    void runsTheCliAndFramesLargeLines() throws Exception {
        List<String> stderr = new CopyOnWriteArrayList<>();
        ClaudeAgentOptions options = options().env("FAKE_TEXT_SIZE", "300000").stderr(stderr::add).build();

        List<Message> messages = drain(ClaudeAgent.query("hi", options));

        assertEquals(2, messages.size());
        assertEquals(300_000, ((AssistantMessage) messages.get(0)).text().length());
        assertInstanceOf(ResultMessage.class, messages.get(1));
        assertEquals(List.of("diagnostic line"), stderr);

        List<String> args = Files.readAllLines(dir.resolve("args"));
        assertEquals(List.of("--output-format", "stream-json", "--verbose"), args.subList(0, 3));
        assertTrue(args.contains("fake-model"));
        String env = Files.readString(dir.resolve("env"));
        assertTrue(env.contains("CLAUDE_CODE_ENTRYPOINT=sdk-java"));
        assertTrue(env.contains("CLAUDE_CODE_SDK_READS_SESSION_STATE=1"));
        assertTrue(env.contains("CLAUDE_AGENT_SDK_VERSION="));
        assertFalse(env.lines().anyMatch(line -> line.startsWith("CLAUDECODE=")));
    }

    @Test
    void nonZeroExitBecomesProcessException() throws Exception {
        MessageStream stream = ClaudeAgent.query("hi", options().env("FAKE_EXIT", "3").build());
        List<Message> seen = new ArrayList<>();
        ProcessException error = assertThrows(ProcessException.class, () -> stream.forEach(seen::add));
        assertEquals(3, error.exitCode());
        assertEquals(2, seen.size());
    }

    @Test
    void oversizedMessageFailsWithDecodeError() throws Exception {
        ClaudeAgentOptions options = options().env("FAKE_TEXT_SIZE", "5000").maxBufferSize(1000).build();
        MessageStream stream = ClaudeAgent.query("hi", options);
        assertThrows(CliJsonDecodeException.class, () -> drain(stream));
    }

    @Test
    void missingCliAndWorkingDirectoryAreReported() throws Exception {
        assertThrows(CliNotFoundException.class, () -> ClaudeAgent.query("hi",
                ClaudeAgentOptions.builder().cliPath(dir.resolve("nope")).build()));
        assertThrows(CliConnectionException.class, () -> ClaudeAgent.query("hi",
                options().cwd(dir.resolve("missing-dir")).build()));
    }
}
