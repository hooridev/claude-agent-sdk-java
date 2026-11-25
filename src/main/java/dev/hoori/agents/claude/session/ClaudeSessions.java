package dev.hoori.agents.claude.session;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.hoori.agents.claude.internal.session.LocalSessionReader;
import dev.hoori.agents.claude.internal.session.SessionPaths;
import dev.hoori.agents.claude.internal.session.StoreSessionReader;

/**
 * Reads session transcripts: from the local Claude config directory
 * ({@code ~/.claude/projects/<sanitized-cwd>/}, honoring {@code CLAUDE_CONFIG_DIR}) or from a
 * {@link SessionStore}.
 *
 * <p>Paging: {@code limit <= 0} means no limit, {@code offset <= 0} means no offset. A
 * {@code directory} of {@code null} means "all projects" for the local functions and "the
 * current working directory" for the {@code *FromStore} functions (which address one project key).
 *
 * <p>Local functions never throw for missing or unreadable files; they return empty results. The
 * {@code *FromStore} functions propagate adapter failures (checked exceptions wrapped in
 * {@link dev.hoori.agents.claude.exception.ClaudeSdkException}), except while listing, where a
 * failing per-session load degrades that row to an empty summary.
 */
public final class ClaudeSessions {

    private ClaudeSessions() {
    }

    // ---------------------------------------------------------------- local: sessions

    /** Lists the sessions of all projects, newest first. */
    public static List<SessionInfo> listSessions() {
        return listSessions(null, 0, 0, true);
    }

    /** Lists the sessions of {@code directory} and its git worktrees, newest first. */
    public static List<SessionInfo> listSessions(String directory) {
        return listSessions(directory, 0, 0, true);
    }

    /**
     * Lists sessions with metadata from cheap head/tail reads, sorted by {@code lastModified} descending.
     *
     * @param directory        project directory, or {@code null} for all projects
     * @param limit            maximum number of sessions; {@code <= 0} for all
     * @param offset           number of sessions to skip
     * @param includeWorktrees with a {@code directory} inside a git repository, include the
     *                         sessions of all its worktrees
     */
    public static List<SessionInfo> listSessions(String directory, int limit, int offset, boolean includeWorktrees) {
        return LocalSessionReader.listSessions(directory, limit, offset, includeWorktrees);
    }

    /** Metadata of a session searched in all projects. */
    public static Optional<SessionInfo> getSessionInfo(String sessionId) {
        return getSessionInfo(sessionId, null);
    }

    /**
     * Metadata of one session, without a directory scan.
     *
     * @param directory project directory (also searched in its git worktrees), or {@code null} for all projects
     * @return empty if the id is not a UUID, the session is not found, is a sidechain or has no summary
     */
    public static Optional<SessionInfo> getSessionInfo(String sessionId, String directory) {
        return Optional.ofNullable(LocalSessionReader.getSessionInfo(sessionId, directory));
    }

    /** All messages of a session searched in all projects. */
    public static List<SessionMessage> getSessionMessages(String sessionId) {
        return getSessionMessages(sessionId, null, 0, 0);
    }

    /** All messages of a session. */
    public static List<SessionMessage> getSessionMessages(String sessionId, String directory) {
        return getSessionMessages(sessionId, directory, 0, 0);
    }

    /**
     * The user/assistant messages of a session in chronological order, rebuilt from the
     * {@code parentUuid} chain. A turn that ran tools in parallel is returned whole (every
     * {@code tool_use} and {@code tool_result}).
     *
     * @return empty if the id is not a UUID, the session is not found, or it has no visible messages
     */
    public static List<SessionMessage> getSessionMessages(String sessionId, String directory, int limit, int offset) {
        return LocalSessionReader.getSessionMessages(sessionId, directory, limit, offset);
    }

    // ---------------------------------------------------------------- local: subagents

    /** Subagent ids of a session searched in all projects. */
    public static List<String> listSubagents(String sessionId) {
        return listSubagents(sessionId, null);
    }

    /**
     * Subagent ids of a session, from the {@code agent-<id>.jsonl} files under its {@code subagents}
     * directory (recursively, e.g. {@code subagents/workflows/<runId>/}).
     *
     * @return empty if the id is not a UUID, the session is not found, or it has no subagents
     */
    public static List<String> listSubagents(String sessionId, String directory) {
        return LocalSessionReader.listSubagents(sessionId, directory);
    }

    /** All messages of a subagent, searched in all projects. */
    public static List<SessionMessage> getSubagentMessages(String sessionId, String agentId) {
        return getSubagentMessages(sessionId, agentId, null, 0, 0);
    }

    /** All messages of a subagent. */
    public static List<SessionMessage> getSubagentMessages(String sessionId, String agentId, String directory) {
        return getSubagentMessages(sessionId, agentId, directory, 0, 0);
    }

    /**
     * A subagent's user/assistant messages in chronological order. Each message's
     * {@code parentToolUseId} / {@code parentAgentId} come from the {@code agent-<id>.meta.json}
     * sidecar ({@code null} if it is missing or unusable).
     */
    public static List<SessionMessage> getSubagentMessages(String sessionId, String agentId, String directory,
                                                           int limit, int offset) {
        return LocalSessionReader.getSubagentMessages(sessionId, agentId, directory, limit, offset);
    }

    // ---------------------------------------------------------------- store: sessions

    /** Lists the stored sessions of the current working directory. */
    public static List<SessionInfo> listSessionsFromStore(SessionStore store) {
        return listSessionsFromStore(store, null, 0, 0);
    }

    /** Lists the stored sessions of {@code directory}. */
    public static List<SessionInfo> listSessionsFromStore(SessionStore store, String directory) {
        return listSessionsFromStore(store, directory, 0, 0);
    }

    /**
     * Lists the stored sessions of {@code directory}'s project key, newest first.
     *
     * <p>With {@link SessionStore#listSessionSummaries} this is one summary call plus one
     * {@link SessionStore#listSessions} call to gap-fill sessions with a missing or stale summary
     * (loaded after paging). Otherwise one {@link SessionStore#load} per session, at most 16
     * concurrently. Git worktrees are not considered.
     *
     * @throws IllegalArgumentException if the store implements neither {@code listSessionSummaries}
     *                                  nor {@code listSessions}
     */
    public static List<SessionInfo> listSessionsFromStore(SessionStore store, String directory, int limit,
                                                          int offset) {
        return StoreSessionReader.listSessions(Objects.requireNonNull(store, "store"), directory, limit, offset);
    }

    /** Metadata of a stored session of the current working directory. */
    public static Optional<SessionInfo> getSessionInfoFromStore(SessionStore store, String sessionId) {
        return getSessionInfoFromStore(store, sessionId, null);
    }

    /**
     * Metadata of one stored session.
     *
     * @return empty if the id is not a UUID, the session is not found, is a sidechain or has no summary
     */
    public static Optional<SessionInfo> getSessionInfoFromStore(SessionStore store, String sessionId,
                                                                String directory) {
        return Optional.ofNullable(
                StoreSessionReader.getSessionInfo(Objects.requireNonNull(store, "store"), sessionId, directory));
    }

    /** All messages of a stored session of the current working directory. */
    public static List<SessionMessage> getSessionMessagesFromStore(SessionStore store, String sessionId) {
        return getSessionMessagesFromStore(store, sessionId, null, 0, 0);
    }

    /** All messages of a stored session. */
    public static List<SessionMessage> getSessionMessagesFromStore(SessionStore store, String sessionId,
                                                                   String directory) {
        return getSessionMessagesFromStore(store, sessionId, directory, 0, 0);
    }

    /** The messages of a stored session; same chain semantics as {@link #getSessionMessages(String, String, int, int)}. */
    public static List<SessionMessage> getSessionMessagesFromStore(SessionStore store, String sessionId,
                                                                   String directory, int limit, int offset) {
        return StoreSessionReader.getSessionMessages(Objects.requireNonNull(store, "store"), sessionId, directory,
                limit, offset);
    }

    // ---------------------------------------------------------------- store: subagents

    /** Subagent ids of a stored session of the current working directory. */
    public static List<String> listSubagentsFromStore(SessionStore store, String sessionId) {
        return listSubagentsFromStore(store, sessionId, null);
    }

    /**
     * Subagent ids of a stored session (deduplicated across direct and nested subpaths).
     *
     * @throws IllegalArgumentException if the store does not implement {@link SessionStore#listSubkeys}
     */
    public static List<String> listSubagentsFromStore(SessionStore store, String sessionId, String directory) {
        return StoreSessionReader.listSubagents(Objects.requireNonNull(store, "store"), sessionId, directory);
    }

    /** All messages of a stored subagent of the current working directory. */
    public static List<SessionMessage> getSubagentMessagesFromStore(SessionStore store, String sessionId,
                                                                    String agentId) {
        return getSubagentMessagesFromStore(store, sessionId, agentId, null, 0, 0);
    }

    /** All messages of a stored subagent. */
    public static List<SessionMessage> getSubagentMessagesFromStore(SessionStore store, String sessionId,
                                                                    String agentId, String directory) {
        return getSubagentMessagesFromStore(store, sessionId, agentId, directory, 0, 0);
    }

    /**
     * A stored subagent's messages. Subagents may live at {@code subagents/agent-<id>} or nested
     * (e.g. {@code subagents/workflows/<runId>/agent-<id>}); nested ones are found only if the
     * store implements {@link SessionStore#listSubkeys}. Parent ids come from the subagent's
     * {@code agent_metadata} entry.
     */
    public static List<SessionMessage> getSubagentMessagesFromStore(SessionStore store, String sessionId,
                                                                    String agentId, String directory, int limit,
                                                                    int offset) {
        return StoreSessionReader.getSubagentMessages(Objects.requireNonNull(store, "store"), sessionId, agentId,
                directory, limit, offset);
    }

    // ---------------------------------------------------------------- project keys

    /** The {@link SessionStore} project key of the current working directory. */
    public static String projectKeyForDirectory() {
        return projectKeyForDirectory(null);
    }

    /**
     * The {@link SessionStore} project key of a directory: realpath + NFC + the CLI's sanitization,
     * so keys match the local project directory names.
     *
     * @param directory the directory, or {@code null} for the current working directory
     */
    public static String projectKeyForDirectory(String directory) {
        return SessionPaths.projectKeyForDirectory(directory);
    }
}
