package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.hoori.agents.claude.internal.session.SessionText.LiteSessionFile;
import dev.hoori.agents.claude.session.SessionInfo;
import dev.hoori.agents.claude.session.SessionMessage;

/**
 * Reads session transcripts from the local Claude config directory (Python: the filesystem read
 * functions of {@code sessions.py}). All methods degrade to empty results instead of throwing.
 */
public final class LocalSessionReader {

    private static final boolean CASE_INSENSITIVE_FS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private LocalSessionReader() {
    }

    // ---------------------------------------------------------------- listing

    /** Sessions of a project directory (and its git worktrees), or of all projects if {@code directory} is empty. */
    public static List<SessionInfo> listSessions(String directory, int limit, int offset, boolean includeWorktrees) {
        List<SessionInfo> sessions = directory != null && !directory.isEmpty()
                ? listSessionsForProject(directory, includeWorktrees)
                : listAllSessions();
        return sortAndPage(sessions, limit, offset);
    }

    /** Sorts by {@code lastModified} descending, then applies offset and limit. */
    static List<SessionInfo> sortAndPage(List<SessionInfo> sessions, int limit, int offset) {
        List<SessionInfo> sorted = new ArrayList<>(sessions);
        sorted.sort(Comparator.comparingLong(SessionInfo::lastModified).reversed());
        return Paging.page(sorted, limit, offset);
    }

    private static List<SessionInfo> listSessionsForProject(String directory, boolean includeWorktrees) {
        String canonical = SessionPaths.canonicalizePath(directory);
        List<String> worktrees = includeWorktrees ? SessionPaths.worktreePaths(canonical) : List.of();
        Path canonicalProjectDir = SessionPaths.findProjectDir(canonical);
        if (worktrees.size() <= 1) {
            return canonicalProjectDir == null ? List.of() : readSessionsFromDir(canonicalProjectDir, canonical);
        }
        List<Path> projectDirs = subdirectories(SessionPaths.projectsDir());
        if (projectDirs == null) {
            return canonicalProjectDir == null ? List.of() : readSessionsFromDir(canonicalProjectDir, canonical);
        }

        // Longest sanitized prefix first, so more specific worktrees win.
        List<String[]> indexed = new ArrayList<>(); // {worktreePath, prefix}
        for (String worktree : worktrees) {
            indexed.add(new String[] {worktree, normalizeCase(SessionPaths.sanitizePath(worktree))});
        }
        indexed.sort(Comparator.comparingInt((String[] w) -> w[1].length()).reversed());

        List<SessionInfo> all = new ArrayList<>();
        Set<String> seenDirs = new HashSet<>();
        // Always include the directory itself (subdirectories of a worktree match no worktree root).
        if (canonicalProjectDir != null) {
            seenDirs.add(normalizeCase(canonicalProjectDir.getFileName().toString()));
            all.addAll(readSessionsFromDir(canonicalProjectDir, canonical));
        }
        for (Path dir : projectDirs) {
            String dirName = normalizeCase(dir.getFileName().toString());
            if (seenDirs.contains(dirName)) {
                continue;
            }
            for (String[] worktree : indexed) {
                String prefix = worktree[1];
                // Prefix matching only for truncated (hash-suffixed) names: /a/b must not match /a/b-c.
                boolean match = dirName.equals(prefix)
                        || (prefix.length() >= SessionPaths.MAX_SANITIZED_LENGTH && dirName.startsWith(prefix + "-"));
                if (match) {
                    seenDirs.add(dirName);
                    all.addAll(readSessionsFromDir(dir, worktree[0]));
                    break;
                }
            }
        }
        return deduplicate(all);
    }

    private static List<SessionInfo> listAllSessions() {
        List<Path> projectDirs = subdirectories(SessionPaths.projectsDir());
        if (projectDirs == null) {
            return List.of();
        }
        List<SessionInfo> all = new ArrayList<>();
        projectDirs.forEach(dir -> all.addAll(readSessionsFromDir(dir, null)));
        return deduplicate(all);
    }

    private static List<SessionInfo> readSessionsFromDir(Path projectDir, String projectPath) {
        List<SessionInfo> results = new ArrayList<>();
        for (Path entry : children(projectDir)) {
            String name = entry.getFileName().toString();
            if (!name.endsWith(".jsonl")) {
                continue;
            }
            String sessionId = SessionPaths.validateUuid(name.substring(0, name.length() - ".jsonl".length()));
            if (sessionId == null) {
                continue;
            }
            LiteSessionFile lite = SessionText.readSessionLite(entry);
            SessionInfo info = lite == null ? null : LiteSessionInfoParser.parse(sessionId, lite, projectPath);
            if (info != null) {
                results.add(info);
            }
        }
        return results;
    }

    /** Keeps the newest {@code lastModified} per session id. */
    private static List<SessionInfo> deduplicate(List<SessionInfo> sessions) {
        Map<String, SessionInfo> byId = new LinkedHashMap<>();
        for (SessionInfo s : sessions) {
            byId.merge(s.sessionId(), s, (old, cur) -> cur.lastModified() > old.lastModified() ? cur : old);
        }
        return new ArrayList<>(byId.values());
    }

    // ---------------------------------------------------------------- single session

    /** Metadata of one session, or {@code null} if not found, a sidechain, or without summary. */
    public static SessionInfo getSessionInfo(String sessionId, String directory) {
        String uuid = SessionPaths.validateUuid(sessionId);
        if (uuid == null) {
            return null;
        }
        String fileName = uuid + ".jsonl";
        if (directory == null || directory.isEmpty()) {
            List<Path> projectDirs = subdirectories(SessionPaths.projectsDir());
            for (Path dir : projectDirs == null ? List.<Path>of() : projectDirs) {
                LiteSessionFile lite = SessionText.readSessionLite(dir.resolve(fileName));
                if (lite != null) {
                    return LiteSessionInfoParser.parse(uuid, lite, null);
                }
            }
            return null;
        }
        String canonical = SessionPaths.canonicalizePath(directory);
        SessionInfo info = readInfoIn(SessionPaths.findProjectDir(canonical), fileName, uuid, canonical);
        if (info != null) {
            return info;
        }
        // Worktree fallback: the session may live under another worktree root.
        for (String worktree : SessionPaths.worktreePaths(canonical)) {
            if (!worktree.equals(canonical)) {
                info = readInfoIn(SessionPaths.findProjectDir(worktree), fileName, uuid, worktree);
                if (info != null) {
                    return info;
                }
            }
        }
        return null;
    }

    /** Parses the session file in {@code projectDir}; {@code null} if absent or unparseable. */
    private static SessionInfo readInfoIn(Path projectDir, String fileName, String sessionId, String projectPath) {
        LiteSessionFile lite = projectDir == null ? null : SessionText.readSessionLite(projectDir.resolve(fileName));
        return lite == null ? null : LiteSessionInfoParser.parse(sessionId, lite, projectPath);
    }

    /** The conversation of a session, root → leaf, with paging. */
    public static List<SessionMessage> getSessionMessages(String sessionId, String directory, int limit, int offset) {
        if (SessionPaths.validateUuid(sessionId) == null) {
            return List.of();
        }
        String content = readFile(SessionPaths.resolveSessionFilePath(sessionId, directory));
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return TranscriptChain.toSessionMessages(TranscriptChain.parseEntries(content), limit, offset);
    }

    // ---------------------------------------------------------------- subagents

    /** Subagent ids of a session (transcripts may be nested, e.g. {@code subagents/workflows/<runId>/}). */
    public static List<String> listSubagents(String sessionId, String directory) {
        if (SessionPaths.validateUuid(sessionId) == null) {
            return List.of();
        }
        Path subagentsDir = subagentsDir(sessionId, directory);
        return subagentsDir == null ? List.of() : List.copyOf(collectAgentFiles(subagentsDir).keySet());
    }

    /** A subagent's conversation; parent ids come from its {@code .meta.json} sidecar. */
    public static List<SessionMessage> getSubagentMessages(String sessionId, String agentId, String directory,
                                                           int limit, int offset) {
        if (SessionPaths.validateUuid(sessionId) == null || agentId == null || agentId.isEmpty()) {
            return List.of();
        }
        Path subagentsDir = subagentsDir(sessionId, directory);
        Path transcript = subagentsDir == null ? null : collectAgentFiles(subagentsDir).get(agentId);
        String content = readFile(transcript);
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        Map<String, Object> meta;
        try {
            meta = SessionPaths.readAgentMetadataSidecar(transcript);
        } catch (IOException e) {
            meta = null; // an unreadable optional sidecar degrades to an absent one
        }
        String[] parentIds = SessionPaths.parentIdsFromAgentMetadata(meta);
        return TranscriptChain.toSubagentMessages(TranscriptChain.parseEntries(content), limit, offset,
                parentIds[0], parentIds[1]);
    }

    /** {@code <projectDir>/<sessionId>/subagents} of the resolved session file, or {@code null}. */
    private static Path subagentsDir(String sessionId, String directory) {
        Path sessionFile = SessionPaths.resolveSessionFilePath(sessionId, directory);
        return sessionFile == null ? null : sessionFile.resolveSibling(sessionId).resolve("subagents");
    }

    /** {@code agent-<id>.jsonl} files under {@code dir}, recursively, in name order; first one wins per id. */
    static Map<String, Path> collectAgentFiles(Path dir) {
        Map<String, Path> result = new LinkedHashMap<>();
        collectAgentFiles(dir, result);
        return result;
    }

    private static void collectAgentFiles(Path dir, Map<String, Path> result) {
        List<Path> entries = children(dir);
        entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (Files.isRegularFile(entry) && name.startsWith("agent-") && name.endsWith(".jsonl")) {
                result.putIfAbsent(name.substring("agent-".length(), name.length() - ".jsonl".length()), entry);
            } else if (Files.isDirectory(entry)) {
                collectAgentFiles(entry, result);
            }
        }
    }

    // ---------------------------------------------------------------- file helpers

    private static String readFile(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Entries of a directory; empty on any error. */
    private static List<Path> children(Path dir) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(result::add);
        } catch (IOException e) {
            // missing or unreadable directory
        }
        return result;
    }

    /** Subdirectories of a directory, or {@code null} if it cannot be read. */
    private static List<Path> subdirectories(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            List<Path> result = new ArrayList<>();
            stream.forEach(result::add);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    private static String normalizeCase(String name) {
        return CASE_INSENSITIVE_FS ? name.toLowerCase(Locale.ROOT) : name;
    }
}
