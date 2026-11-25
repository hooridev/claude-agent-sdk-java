package dev.hoori.agents.claude.internal.session;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionListSubkeysKey;
import dev.hoori.agents.claude.session.SessionStore;
import dev.hoori.agents.claude.session.SessionStoreListEntry;
import dev.hoori.agents.claude.session.SessionStores;

/**
 * Store-backed resume: the CLI can only resume from a local file, so the session (and its
 * subagent transcripts) is loaded from {@code options.sessionStore()} into a temporary config
 * directory the subprocess is pointed at (Python: {@code session_resume.py}).
 */
public final class SessionResume {

    private static final Logger LOG = System.getLogger(SessionResume.class.getName());
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:");

    private SessionResume() {
    }

    private record Loaded(String sessionId, List<Map<String, Object>> entries) {
    }

    /**
     * Materializes the session to resume.
     *
     * @return {@code null} when nothing needs materializing: no store, neither {@code resume} nor
     *         {@code continueConversation}, a non-UUID resume id, or no entries in the store
     * @throws ClaudeSdkException if a store call fails or exceeds {@code loadTimeoutMs}; the
     *                            temporary directory is removed before throwing
     */
    public static MaterializedResume materialize(ClaudeAgentOptions options) {
        return materialize(options, ResumeConfigSeeder.system());
    }

    static MaterializedResume materialize(ClaudeAgentOptions options, ResumeConfigSeeder seeder) {
        SessionStore store = options.sessionStore();
        if (store == null || (options.resume() == null && !options.continueConversation())) {
            return null;
        }
        Duration timeout = Duration.ofMillis(options.loadTimeoutMs());
        String projectKey = SessionPaths.projectKeyForDirectory(options.cwd() == null ? null : options.cwd().toString());
        Loaded resolved;
        if (options.resume() != null) {
            // The id becomes a path component: anything but a UUID could traverse.
            if (SessionPaths.validateUuid(options.resume()) == null) {
                return null;
            }
            resolved = loadCandidate(store, projectKey, options.resume(), timeout);
        } else {
            resolved = resolveContinueCandidate(store, projectKey, timeout);
        }
        if (resolved == null) {
            return null;
        }

        Path tmpBase;
        try {
            tmpBase = Files.createTempDirectory("claude-resume-"); // owner-only on POSIX
            MaterializedResume.track(tmpBase); // before credentials are copied into it
        } catch (IOException e) {
            throw new ClaudeSdkException("Could not create a temporary directory for resume materialization", e);
        }
        boolean complete = false;
        try {
            Path projectDir = tmpBase.resolve("projects").resolve(projectKey);
            Files.createDirectories(projectDir);
            writeJsonl(projectDir.resolve(resolved.sessionId() + ".jsonl"), resolved.entries());
            seeder.seed(tmpBase, options.env());
            if (SessionStores.implementsMethod(store, "listSubkeys")) {
                materializeSubkeys(store, projectDir, projectKey, resolved.sessionId(), timeout);
            }
            complete = true;
            return new MaterializedResume(tmpBase, resolved.sessionId());
        } catch (IOException e) {
            throw new ClaudeSdkException("Resume materialization failed: " + e.getMessage(), e);
        } finally {
            if (!complete) {
                // The directory may already hold a credentials copy and nobody else can remove it.
                MaterializedResume.deleteWithRetry(tmpBase);
            }
        }
    }

    /**
     * A copy of {@code options} pointed at the materialized directory: {@code CLAUDE_CONFIG_DIR}
     * in {@code env}, {@code resume} set to the resolved id, {@code continueConversation} cleared.
     */
    public static ClaudeAgentOptions apply(ClaudeAgentOptions options, MaterializedResume materialized) {
        Map<String, String> env = new LinkedHashMap<>(options.env());
        env.put("CLAUDE_CONFIG_DIR", materialized.configDir().toString());
        return options.toBuilder()
                .env(env)
                .resume(materialized.resumeSessionId())
                .continueConversation(false)
                .build();
    }

    private static Loaded loadCandidate(SessionStore store, String projectKey, String sessionId, Duration timeout) {
        List<Map<String, Object>> entries = withTimeout(() -> store.load(SessionKey.of(projectKey, sessionId)),
                timeout, "SessionStore.load() for session " + sessionId);
        return entries == null || entries.isEmpty() ? null : new Loaded(sessionId, entries);
    }

    /**
     * The most recently modified non-sidechain session. Sidechains are mirrored as top-level keys
     * and often have the newest mtime, so candidates are loaded newest first and skipped if their
     * first entry is a sidechain (like the CLI's own {@code --continue}).
     */
    private static Loaded resolveContinueCandidate(SessionStore store, String projectKey, Duration timeout) {
        List<SessionStoreListEntry> sessions = withTimeout(() -> store.listSessions(projectKey), timeout,
                "SessionStore.listSessions()");
        if (sessions == null) {
            return null;
        }
        List<SessionStoreListEntry> newestFirst = sessions.stream()
                .sorted(Comparator.comparingLong(SessionStoreListEntry::mtime).reversed()) // stable on ties
                .toList();
        for (SessionStoreListEntry candidate : newestFirst) {
            if (SessionPaths.validateUuid(candidate.sessionId()) == null) {
                continue;
            }
            Loaded loaded = loadCandidate(store, projectKey, candidate.sessionId(), timeout);
            if (loaded != null && !Json.isTrue(loaded.entries().getFirst(), "isSidechain")) {
                return loaded;
            }
        }
        return null;
    }

    private static void materializeSubkeys(SessionStore store, Path projectDir, String projectKey, String sessionId,
                                           Duration timeout) throws IOException {
        Path sessionDir = projectDir.resolve(sessionId);
        List<String> subkeys = withTimeout(() -> store.listSubkeys(new SessionListSubkeysKey(projectKey, sessionId)),
                timeout, "SessionStore.listSubkeys() for session " + sessionId);
        for (String subpath : subkeys == null ? List.<String>of() : subkeys) {
            Path subFile = safeSubagentFile(subpath, sessionDir);
            if (subFile == null) {
                LOG.log(Level.WARNING, "[SessionStore] skipping unsafe subpath from listSubkeys: " + subpath);
                continue;
            }
            List<Map<String, Object>> entries = withTimeout(
                    () -> store.load(new SessionKey(projectKey, sessionId, subpath)), timeout,
                    "SessionStore.load() for session " + sessionId + " subpath " + subpath);
            if (entries == null || entries.isEmpty()) {
                continue;
            }
            SessionPaths.SplitMetadata split = SessionPaths.splitAgentMetadata(entries);
            if (!split.transcript().isEmpty()) {
                writeJsonl(subFile, split.transcript());
            }
            if (split.metadata() != null) {
                Map<String, Object> meta = new LinkedHashMap<>(split.metadata());
                meta.remove("type"); // the synthetic agent_metadata discriminator
                Path metaFile = SessionPaths.agentMetadataSidecarPath(subFile);
                Files.createDirectories(metaFile.getParent());
                FileOps.writePrivate(metaFile, Json.write(meta).getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * The {@code .jsonl} target of a store subpath, or {@code null} if the subpath is empty,
     * absolute, drive-prefixed, contains {@code .}/{@code ..} segments or NUL, or escapes
     * {@code sessionDir}. Subpaths come from an external store and become file paths.
     */
    static Path safeSubagentFile(String subpath, Path sessionDir) {
        if (subpath == null || subpath.isEmpty() || subpath.indexOf('\0') >= 0
                || subpath.startsWith("/") || subpath.startsWith("\\") || DRIVE_PREFIX.matcher(subpath).find()) {
            return null;
        }
        for (String segment : subpath.split("[\\\\/]", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        try {
            if (Path.of(subpath).isAbsolute()) {
                return null;
            }
            Path target = sessionDir.resolve(subpath);
            Path file = target.resolveSibling(target.getFileName() + ".jsonl").toAbsolutePath().normalize();
            return file.startsWith(sessionDir.toAbsolutePath().normalize()) ? file : null;
        } catch (RuntimeException e) {
            return null; // InvalidPathException and friends
        }
    }

    /** One compact JSON line per entry, mode 0600. */
    static void writeJsonl(Path path, List<?> entries) throws IOException {
        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Object entry : entries) {
                writer.write(Json.write(entry));
                writer.write('\n');
            }
        }
        FileOps.restrictToOwner(path);
    }

    private static <T> T withTimeout(Callable<T> call, Duration timeout, String what) {
        try {
            return TimedCalls.call(call, timeout, "session-resume-load");
        } catch (TimeoutException e) {
            throw new ClaudeSdkException(what + " timed out after " + timeout.toMillis()
                    + "ms during resume materialization", e);
        } catch (InterruptedException e) {
            throw new ClaudeSdkException(what + " was interrupted during resume materialization", e);
        } catch (Exception e) {
            throw new ClaudeSdkException(what + " failed during resume materialization: " + e.getMessage(), e);
        }
    }
}
