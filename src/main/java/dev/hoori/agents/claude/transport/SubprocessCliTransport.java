package dev.hoori.agents.claude.transport;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.exception.CliConnectionException;
import dev.hoori.agents.claude.exception.CliJsonDecodeException;
import dev.hoori.agents.claude.exception.CliNotFoundException;
import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.exception.ProcessException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.SdkInfo;

/**
 * Runs the locally installed Claude Code CLI as a subprocess and talks NDJSON over its
 * stdin/stdout (Python: {@code SubprocessCLITransport}).
 */
public final class SubprocessCliTransport implements Transport {

    private static final System.Logger LOG = System.getLogger(SubprocessCliTransport.class.getName());

    static final int DEFAULT_MAX_BUFFER_SIZE = 1024 * 1024;
    static final String MINIMUM_CLAUDE_CODE_VERSION = "2.0.0";
    static final String VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION = "2.1.248";
    static final String EXPLICIT_CLIENT_APP_ENV = "CLAUDE_AGENT_SDK_CLIENT_APP";
    static final String DERIVED_CLIENT_APP_ENV = "CLAUDE_AGENT_SDK_DERIVED_CLIENT_APP";
    /** Asks the CLI for {@code session_state_changed} frames the SDK reads to tell when a run is over. */
    static final String SDK_READS_SESSION_STATE_ENV = "CLAUDE_CODE_SDK_READS_SESSION_STATE";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})");
    private static final long GRACEFUL_EXIT_SECONDS = 5;

    /** Live CLI processes, terminated if the JVM exits before they were closed. */
    private static final Set<Process> ACTIVE_CHILDREN = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(
                // ProcessHandle only signals; Process.destroy() also closes pipes and can block JVM exit.
                () -> ACTIVE_CHILDREN.forEach(child -> child.toHandle().destroy())));
    }

    private final ClaudeAgentOptions options;
    private final int maxBufferSize;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile Process process;
    private volatile Writer stdin;
    private volatile boolean ready;
    /** Set by {@link #close()}: the exit code of a process we are terminating is not an error. */
    private volatile boolean closing;
    private volatile ClaudeSdkException exitError;
    private Thread stderrThread;
    private String cliPath;
    private boolean versionChecked = System.getenv("CLAUDE_AGENT_SDK_SKIP_VERSION_CHECK") != null;

    public SubprocessCliTransport(ClaudeAgentOptions options) {
        this.options = options;
        this.cliPath = options.cliPath() == null ? null : options.cliPath().toString();
        this.maxBufferSize = options.maxBufferSize() != null ? options.maxBufferSize() : DEFAULT_MAX_BUFFER_SIZE;
    }

    @Override
    public void connect() {
        if (process != null) {
            return;
        }
        closing = false; // a reconnect after close() must report its own exit codes again
        if (cliPath == null) {
            cliPath = CliLocator.find().toString();
        }
        CliCommandBuilder commandBuilder = new CliCommandBuilder(options);
        commandBuilder.rejectWindowsBatchCli(cliPath);
        List<String> command = commandBuilder.build(cliPath);

        ProcessBuilder builder = new ProcessBuilder(command);
        buildEnvironment(builder.environment());
        if (options.cwd() != null) {
            builder.directory(options.cwd().toFile());
        }
        // Pipe stderr only when the caller registered a callback.
        builder.redirectError(options.stderr() != null ? ProcessBuilder.Redirect.PIPE
                : ProcessBuilder.Redirect.INHERIT);
        try {
            Process started = builder.start();
            ACTIVE_CHILDREN.add(started);
            process = started;
            stdin = new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8);
            if (options.stderr() != null) {
                stderrThread = Thread.ofVirtual().name("claude-stderr").start(
                        () -> pumpStderr(started, options.stderr()));
            }
            ready = true;
        } catch (IOException e) {
            ClaudeSdkException error;
            if (options.cwd() != null && !Files.isDirectory(options.cwd())) {
                error = new CliConnectionException("Working directory does not exist: " + options.cwd(), e);
            } else if (!Files.exists(Path.of(cliPath))) {
                error = new CliNotFoundException("Claude Code not found at", cliPath);
            } else {
                error = new CliConnectionException("Failed to start Claude Code: " + e.getMessage(), e);
            }
            exitError = error;
            throw error;
        }
    }

    private void buildEnvironment(Map<String, String> env) {
        // Don't let the CLI think it runs inside a Claude Code parent.
        env.remove("CLAUDECODE");
        env.put("CLAUDE_CODE_ENTRYPOINT", SdkInfo.ENTRYPOINT);
        env.putAll(options.env());
        env.put("CLAUDE_AGENT_SDK_VERSION", SdkInfo.VERSION);
        // A label from an outer SDK session names that session's app, not ours.
        env.remove(DERIVED_CLIENT_APP_ENV);
        if (options.clientApp() != null) {
            env.put(EXPLICIT_CLIENT_APP_ENV, options.clientApp());
        }
        boolean stateEnvSet = env.keySet().stream().anyMatch(SDK_READS_SESSION_STATE_ENV::equalsIgnoreCase);
        if (!stateEnvSet) {
            env.put(SDK_READS_SESSION_STATE_ENV, "1");
        }
        if (options.enableFileCheckpointing()) {
            env.put("CLAUDE_CODE_ENABLE_SDK_FILE_CHECKPOINTING", "true");
        }
        if (options.cwd() != null) {
            env.put("PWD", options.cwd().toString());
        }
    }

    private void pumpStderr(Process source, Consumer<String> callback) {
        BoundedLineReader reader = new BoundedLineReader(
                new InputStreamReader(source.getErrorStream(), StandardCharsets.UTF_8), maxBufferSize, true);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.stripTrailing();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    callback.accept(trimmed);
                } catch (RuntimeException e) {
                    // Isolated per line so one failing callback does not drop all later lines.
                    LOG.log(Level.DEBUG, "stderr callback raised; continuing", e);
                }
            }
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "stderr stream read failed", e);
        }
    }

    @Override
    public void write(String data) {
        writeLock.lock();
        try {
            if (!ready || stdin == null) {
                throw new CliConnectionException("ProcessTransport is not ready for writing");
            }
            Process current = process;
            if (current != null && !current.isAlive()) {
                throw new CliConnectionException(
                        "Cannot write to terminated process (exit code: " + current.exitValue() + ")");
            }
            if (exitError != null) {
                throw new CliConnectionException("Cannot write to process that exited with error: " + exitError,
                        exitError);
            }
            Writer target = stdin;
            try {
                target.write(data);
                target.flush();
            } catch (IOException e) {
                CliConnectionException error =
                        new CliConnectionException("Failed to write to process stdin: " + e.getMessage(), e);
                if (stdin == target) { // a writer of an already replaced process must not fail the new one
                    ready = false;
                    exitError = error;
                }
                throw error;
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void endInput() {
        writeLock.lock();
        try {
            closeStdinQuietly();
        } finally {
            writeLock.unlock();
        }
    }

    private void closeStdinQuietly() {
        Writer current = stdin;
        stdin = null;
        closeQuietly(current);
    }

    private static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // already closed by the process
            }
        }
    }

    @Override
    public Iterator<Map<String, Object>> readMessages() {
        Process current = process;
        if (current == null) {
            throw new CliConnectionException("Not connected");
        }
        BoundedLineReader reader = new BoundedLineReader(
                new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8), maxBufferSize, false);
        return new StdoutIterator(current, reader);
    }

    /** Iterates the NDJSON messages on stdout; at EOF turns a non-zero exit into a {@link ProcessException}. */
    private final class StdoutIterator implements Iterator<Map<String, Object>> {
        private final Process source;
        private final BoundedLineReader reader;
        private Map<String, Object> next;
        private boolean finished;

        StdoutIterator(Process source, BoundedLineReader reader) {
            this.source = source;
            this.reader = reader;
        }

        @Override
        public boolean hasNext() {
            while (next == null && !finished) {
                String line;
                try {
                    line = reader.readLine();
                } catch (BoundedLineReader.LineTooLongException e) {
                    finished = true;
                    throw new CliJsonDecodeException(e.getMessage(), e);
                } catch (IOException e) {
                    line = null; // stream closed by close()
                }
                if (line == null) {
                    finished = true;
                    checkExit();
                    return false;
                }
                next = parseLine(line);
            }
            return next != null;
        }

        @Override
        public Map<String, Object> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Map<String, Object> result = next;
            next = null;
            return result;
        }

        private Map<String, Object> parseLine(String line) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                return null;
            }
            if (!trimmed.startsWith("{")) {
                // Some CLI builds write non-JSON debug output (e.g. "[SandboxDebug] ...") to stdout.
                LOG.log(Level.DEBUG, "Skipping non-JSON line from CLI stdout: {0}",
                        trimmed.substring(0, Math.min(200, trimmed.length())));
                return null;
            }
            try {
                Map<String, Object> data = Json.parseObject(trimmed);
                checkClaudeVersion(data);
                return data;
            } catch (RuntimeException e) {
                throw new CliJsonDecodeException(trimmed, e);
            }
        }

        private void checkExit() {
            int exitCode;
            try {
                exitCode = source.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (exitCode != 0 && !closing) {
                ProcessException error = new ProcessException("Command failed with exit code " + exitCode, exitCode,
                        "Check stderr output for details");
                exitError = error;
                throw error;
            }
        }
    }

    /** Warns once if the CLI's init message shows an old version. */
    private void checkClaudeVersion(Map<String, Object> data) {
        if (versionChecked || !"system".equals(data.get("type")) || !"init".equals(data.get("subtype"))) {
            return;
        }
        versionChecked = true;
        String version = String.valueOf(data.get("claude_code_version"));
        Matcher matcher = VERSION_PATTERN.matcher(version);
        boolean known = matcher.lookingAt();
        if (!known) {
            LOG.log(Level.WARNING, "Claude Code at {0} is too old to report its version, and may be unsupported. "
                    + "Minimum required version is {1}.", cliPath, MINIMUM_CLAUDE_CODE_VERSION);
        }
        if (options.verbatimPrompts() && (!known
                || compareVersions(matcher, VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION) < 0)) {
            LOG.log(Level.WARNING, "verbatimPrompts is enabled, but Claude Code version {0} at {1} ignores it. "
                    + "Claude Code {2} or later is required.", version, cliPath,
                    VERBATIM_PROMPTS_MINIMUM_CLAUDE_CODE_VERSION);
        }
    }

    private static int compareVersions(Matcher actual, String minimum) {
        String[] parts = minimum.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Long.compare(Long.parseLong(actual.group(i + 1)), Long.parseLong(parts[i]));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /**
     * Closes stdin, gives the CLI time to flush its session file, then escalates to SIGTERM and
     * SIGKILL. Bounded to roughly 20 seconds.
     */
    @Override
    public void close() {
        Process current = process;
        if (current == null) {
            ready = false;
            return;
        }
        closing = true;
        boolean locked = false;
        try {
            locked = writeLock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!locked) {
            // A writer is stuck on a full stdin pipe and holds the writer's monitor: end the process
            // first so the write fails and closing stdin cannot block. Signal via ProcessHandle:
            // Process.destroy() would itself close stdin and block on that monitor.
            current.toHandle().destroy();
            if (!waitFor(current)) {
                current.toHandle().destroyForcibly();
                waitFor(current);
            }
        }
        ready = false;
        if (locked) {
            try {
                closeStdinQuietly();
            } finally {
                writeLock.unlock();
            }
        } else {
            // A descendant may still hold the pipe and keep the writer blocked: close off this thread.
            Writer retired = stdin;
            stdin = null;
            Thread.ofVirtual().name("claude-stdin-close").start(() -> closeQuietly(retired));
        }
        try {
            // ProcessHandle signals only: Process.destroy() also closes the pipes and can block on them.
            if (!waitFor(current)) {
                current.toHandle().destroy();
                if (!waitFor(current)) {
                    current.toHandle().destroyForcibly();
                    waitFor(current);
                }
            }
        } finally {
            // Only stop tracking a child that is gone; the shutdown hook still gets a live one.
            if (!current.isAlive()) {
                ACTIVE_CHILDREN.remove(current);
            }
            if (stderrThread != null) {
                try {
                    stderrThread.join(TimeUnit.SECONDS.toMillis(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            process = null;
            exitError = null;
        }
    }

    private static boolean waitFor(Process process) {
        try {
            return process.waitFor(GRACEFUL_EXIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Keep escalating: an interrupted close must still not leak the child.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Visible for tests: the command line this transport would run. */
    List<String> buildCommand(String cli) {
        return new CliCommandBuilder(options).build(cli);
    }

    /** Visible for tests: the environment this transport would pass. */
    Map<String, String> buildEnvironmentForTest(Map<String, String> inherited) {
        Map<String, String> env = new HashMap<>(inherited);
        buildEnvironment(env);
        return env;
    }
}
