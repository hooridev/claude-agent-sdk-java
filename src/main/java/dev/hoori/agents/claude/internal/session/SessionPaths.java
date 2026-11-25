package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;

/**
 * Locations and names of session transcripts on disk, shared by the session modules
 * (Python: the path helpers of {@code _internal/sessions.py} and {@code session_store.py}).
 *
 * <p>Layout: {@code <config>/projects/<sanitized-cwd>/<sessionId>.jsonl}, subagents under
 * {@code <sessionId>/subagents/agent-<id>.jsonl} with an optional {@code agent-<id>.meta.json}.
 */
public final class SessionPaths {

    /** Maximum length of a sanitized path component before it is truncated and hashed. */
    public static final int MAX_SANITIZED_LENGTH = 200;

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    private SessionPaths() {
    }

    /** Returns {@code value} if it is a UUID, else {@code null}. */
    public static String validateUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches() ? value : null;
    }

    /** The CLI's 32-bit string hash in base 36 (JS {@code (h << 5) - h + charCode | 0}, over UTF-16 units). */
    public static String simpleHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = (h << 5) - h + s.charAt(i);
        }
        return Long.toString(Math.abs((long) h), 36);
    }

    /** Replaces every non-alphanumeric UTF-16 unit with {@code -}; long results are truncated and hashed. */
    public static String sanitizePath(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            sb.append(alnum ? c : '-');
        }
        if (sb.length() <= MAX_SANITIZED_LENGTH) {
            return sb.toString();
        }
        return sb.substring(0, MAX_SANITIZED_LENGTH) + "-" + simpleHash(name);
    }

    static String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    /** System property that overrides {@code CLAUDE_CONFIG_DIR} (Java cannot set env vars, e.g. in tests). */
    public static final String CONFIG_DIR_PROPERTY = "claude.configDir";

    /**
     * The Claude config directory: system property {@value #CONFIG_DIR_PROPERTY}, else
     * {@code CLAUDE_CONFIG_DIR}, else {@code ~/.claude}.
     */
    public static Path configHomeDir() {
        String configDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (configDir == null || configDir.isEmpty()) {
            configDir = System.getenv("CLAUDE_CONFIG_DIR");
        }
        if (configDir != null && !configDir.isEmpty()) {
            return Path.of(nfc(configDir));
        }
        return Path.of(nfc(Path.of(System.getProperty("user.home"), ".claude").toString()));
    }

    /** The projects directory; {@code envOverride}'s {@code CLAUDE_CONFIG_DIR} wins over the process env. */
    public static Path projectsDir(Map<String, String> envOverride) {
        if (envOverride != null) {
            String override = envOverride.get("CLAUDE_CONFIG_DIR");
            if (override != null && !override.isEmpty()) {
                return Path.of(nfc(override)).resolve("projects");
            }
        }
        return configHomeDir().resolve("projects");
    }

    public static Path projectsDir() {
        return projectsDir(null);
    }

    public static Path projectDir(String projectPath) {
        return projectsDir().resolve(sanitizePath(projectPath));
    }

    /** Resolves symlinks and NFC-normalizes; falls back to the absolute, normalized path. */
    public static String canonicalizePath(String directory) {
        Path path = Path.of(directory);
        try {
            return nfc(path.toRealPath().toString());
        } catch (IOException e) {
            return nfc(path.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * The project directory of a path. For long paths (whose hash suffix may differ between the
     * CLI's runtimes) falls back to a prefix match.
     */
    public static Path findProjectDir(String projectPath) {
        Path exact = projectDir(projectPath);
        if (Files.isDirectory(exact)) {
            return exact;
        }
        String sanitized = sanitizePath(projectPath);
        if (sanitized.length() <= MAX_SANITIZED_LENGTH) {
            return null;
        }
        String prefix = sanitized.substring(0, MAX_SANITIZED_LENGTH) + "-";
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(projectsDir())) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && entry.getFileName().toString().startsWith(prefix)) {
                    return entry;
                }
            }
        } catch (IOException ignored) {
            // no projects directory
        }
        return null;
    }

    /** Absolute worktree paths of the git repository containing {@code cwd}; empty if none or no git. */
    public static List<String> worktreePaths(String cwd) {
        try {
            Process process = new ProcessBuilder("git", "worktree", "list", "--porcelain")
                    .directory(Path.of(cwd).toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0 || output.isEmpty()) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            for (String line : output.split("\n")) {
                if (line.startsWith("worktree ")) {
                    paths.add(nfc(line.substring("worktree ".length())));
                }
            }
            return paths;
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * The path of a session's JSONL file: in {@code directory}'s project dir and its git worktrees,
     * or (without directory) in any project dir. Only non-empty files count.
     */
    public static Path resolveSessionFilePath(String sessionId, String directory) {
        String fileName = sessionId + ".jsonl";
        if (directory != null && !directory.isEmpty()) {
            String canonical = canonicalizePath(directory);
            Path found = nonEmptyCandidate(findProjectDir(canonical), fileName);
            if (found != null) {
                return found;
            }
            for (String worktree : worktreePaths(canonical)) {
                if (worktree.equals(canonical)) {
                    continue;
                }
                found = nonEmptyCandidate(findProjectDir(worktree), fileName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(projectsDir())) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    Path found = nonEmptyCandidate(entry, fileName);
                    if (found != null) {
                        return found;
                    }
                }
            }
        } catch (IOException ignored) {
            // no projects directory
        }
        return null;
    }

    private static Path nonEmptyCandidate(Path projectDir, String fileName) {
        if (projectDir == null) {
            return null;
        }
        Path candidate = projectDir.resolve(fileName);
        try {
            return Files.size(candidate) > 0 ? candidate : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code agent-<id>.jsonl} → {@code agent-<id>.meta.json} in the same directory. */
    public static Path agentMetadataSidecarPath(Path transcriptPath) {
        String name = transcriptPath.getFileName().toString();
        String base = name.endsWith(".jsonl") ? name.substring(0, name.length() - ".jsonl".length()) : name;
        return transcriptPath.resolveSibling(base + ".meta.json");
    }

    /**
     * Reads the {@code .meta.json} sidecar of a subagent transcript.
     *
     * @return the metadata, or {@code null} if missing, invalid JSON or not an object
     * @throws IOException for other read errors (e.g. permission denied)
     */
    public static Map<String, Object> readAgentMetadataSidecar(Path transcriptPath) throws IOException {
        String text;
        try {
            text = Files.readString(agentMetadataSidecarPath(transcriptPath), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        }
        try {
            return Json.parseObject(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Result of {@link #splitAgentMetadata(List)}. */
    public record SplitMetadata(Map<String, Object> metadata, List<Object> transcript) {
    }

    /**
     * Separates the synthetic {@code agent_metadata} entries of a subagent store stream from its
     * transcript lines; the last metadata entry wins.
     */
    @SuppressWarnings("unchecked")
    public static SplitMetadata splitAgentMetadata(List<?> entries) {
        Map<String, Object> metadata = null;
        List<Object> transcript = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && "agent_metadata".equals(map.get("type"))) {
                metadata = new java.util.LinkedHashMap<>((Map<String, Object>) map);
            } else {
                transcript.add(entry);
            }
        }
        return new SplitMetadata(metadata, transcript);
    }

    /** {@code (toolUseId, parentAgentId)} of an agent metadata object; entries may be {@code null}. */
    public static String[] parentIdsFromAgentMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return new String[] {null, null};
        }
        return new String[] {Json.string(metadata, "toolUseId"), Json.string(metadata, "parentAgentId")};
    }

    /** The {@code SessionStore} project key of a directory (default: the working directory). */
    public static String projectKeyForDirectory(String directory) {
        return sanitizePath(canonicalizePath(directory != null ? directory : "."));
    }

    /**
     * Derives a {@link SessionKey} from an absolute transcript path:
     * {@code <projects>/<projectKey>/<sessionId>.jsonl} or
     * {@code <projects>/<projectKey>/<sessionId>/subagents/.../agent-<id>.jsonl}.
     *
     * @return the key, or {@code null} if the path is outside {@code projectsDir} or has another shape
     */
    public static SessionKey filePathToSessionKey(String filePath, String projectsDir) {
        Path relative;
        try {
            relative = Path.of(projectsDir).toAbsolutePath().normalize()
                    .relativize(Path.of(filePath).toAbsolutePath().normalize());
        } catch (IllegalArgumentException e) {
            return null; // different roots (e.g. Windows drives)
        }
        List<String> parts = new ArrayList<>();
        relative.forEach(part -> parts.add(part.toString()));
        if (parts.isEmpty() || parts.getFirst().equals("..") || parts.getFirst().isEmpty() || relative.isAbsolute()
                || parts.size() < 2) {
            return null;
        }
        String projectKey = parts.get(0);
        String second = parts.get(1);
        if (parts.size() == 2 && second.endsWith(".jsonl")) {
            return SessionKey.of(projectKey, second.substring(0, second.length() - ".jsonl".length()));
        }
        if (parts.size() >= 4) {
            String[] subpath = parts.subList(2, parts.size()).toArray(String[]::new);
            String last = subpath[subpath.length - 1];
            if (last.endsWith(".jsonl")) {
                subpath[subpath.length - 1] = last.substring(0, last.length() - ".jsonl".length());
            }
            // Always '/'-joined so keys are portable across platforms.
            return new SessionKey(projectKey, second, String.join("/", Arrays.asList(subpath)));
        }
        return null;
    }
}
