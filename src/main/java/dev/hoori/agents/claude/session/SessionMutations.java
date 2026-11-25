package dev.hoori.agents.claude.session;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.session.MetadataEntries;
import dev.hoori.agents.claude.internal.session.SessionFileMutator;
import dev.hoori.agents.claude.internal.session.SessionForker;
import dev.hoori.agents.claude.internal.session.SessionImporter;
import dev.hoori.agents.claude.internal.session.SessionPaths;
import dev.hoori.agents.claude.internal.session.TranscriptMirrorBatcher;

/**
 * Renames, tags, deletes, forks and imports sessions, on local disk or in a {@link SessionStore}.
 *
 * <p>Local variants: {@code directory} is the project path (not the storage dir), with the same
 * semantics as session listing; {@code null} searches every project directory. They throw
 * {@link java.nio.file.NoSuchFileException} when the session file cannot be found.
 *
 * <p>Store variants: {@code directory} only determines the project key (default: the working
 * directory). Store exceptions propagate unchanged.
 *
 * <p>All methods throw {@link IllegalArgumentException} for an invalid session id or argument.
 */
public final class SessionMutations {

    private SessionMutations() {
    }

    // ------------------------------------------------------------------ local disk

    /** {@link #renameSession(String, String, String)} searching all project directories. */
    public static void renameSession(String sessionId, String title) throws IOException {
        renameSession(sessionId, title, null);
    }

    /**
     * Renames a session by appending a {@code custom-title} entry; the last one wins, so repeated
     * calls are safe.
     *
     * @param title new title; stripped, must be non-empty
     */
    public static void renameSession(String sessionId, String title, String directory) throws IOException {
        SessionFileMutator.append(sessionId, MetadataEntries.customTitle(sessionId, title), directory);
    }

    /** {@link #tagSession(String, String, String)} searching all project directories. */
    public static void tagSession(String sessionId, String tag) throws IOException {
        tagSession(sessionId, tag, null);
    }

    /**
     * Tags a session by appending a {@code tag} entry; the last one wins.
     *
     * @param tag the tag (Unicode-sanitized and stripped, must stay non-empty), or {@code null} to clear it
     */
    public static void tagSession(String sessionId, String tag, String directory) throws IOException {
        SessionFileMutator.append(sessionId, MetadataEntries.tag(sessionId, tag), directory);
    }

    /** {@link #deleteSession(String, String)} searching all project directories. */
    public static void deleteSession(String sessionId) throws IOException {
        deleteSession(sessionId, null);
    }

    /**
     * Hard-deletes a session: its JSONL file and the sibling {@code <sessionId>/} directory of
     * subagent transcripts. For a soft delete, tag the session (e.g. {@code __hidden}) and filter.
     */
    public static void deleteSession(String sessionId, String directory) throws IOException {
        MetadataEntries.requireUuid(sessionId, "session_id");
        SessionFileMutator.delete(sessionId, directory);
    }

    /** {@link #forkSession(String, String, String, String)} searching all project directories. */
    public static ForkSessionResult forkSession(String sessionId) throws IOException {
        return forkSession(sessionId, null, null, null);
    }

    /** {@link #forkSession(String, String, String, String)} of the whole transcript with a derived title. */
    public static ForkSessionResult forkSession(String sessionId, String directory) throws IOException {
        return forkSession(sessionId, directory, null, null);
    }

    /**
     * Forks a session into a new session file with fresh UUIDs (the {@code parentUuid} chain is
     * preserved). The fork starts without undo history (file-history snapshots are not copied).
     *
     * @param upToMessageId cut the transcript after this message uuid (inclusive); {@code null} copies all
     * @param title         title of the fork; {@code null} derives "&lt;original title&gt; (fork)"
     * @throws IllegalArgumentException also if there is nothing to fork or {@code upToMessageId} is unknown
     */
    public static ForkSessionResult forkSession(String sessionId, String directory, String upToMessageId,
                                                String title) throws IOException {
        return new ForkSessionResult(SessionForker.forkOnDisk(sessionId, directory, upToMessageId, title));
    }

    // ------------------------------------------------------------------ SessionStore

    /** {@link #renameSessionViaStore(SessionStore, String, String, String)} for the working directory. */
    public static void renameSessionViaStore(SessionStore store, String sessionId, String title) throws Exception {
        renameSessionViaStore(store, sessionId, title, null);
    }

    /** Store counterpart of {@link #renameSession(String, String, String)}. */
    public static void renameSessionViaStore(SessionStore store, String sessionId, String title, String directory)
            throws Exception {
        appendStamped(store, sessionId, MetadataEntries.customTitle(sessionId, title), directory);
    }

    /** {@link #tagSessionViaStore(SessionStore, String, String, String)} for the working directory. */
    public static void tagSessionViaStore(SessionStore store, String sessionId, String tag) throws Exception {
        tagSessionViaStore(store, sessionId, tag, null);
    }

    /** Store counterpart of {@link #tagSession(String, String, String)}; {@code null} clears the tag. */
    public static void tagSessionViaStore(SessionStore store, String sessionId, String tag, String directory)
            throws Exception {
        appendStamped(store, sessionId, MetadataEntries.tag(sessionId, tag), directory);
    }

    /** {@link #deleteSessionViaStore(SessionStore, String, String)} for the working directory. */
    public static void deleteSessionViaStore(SessionStore store, String sessionId) throws Exception {
        deleteSessionViaStore(store, sessionId, null);
    }

    /**
     * Deletes a session from a store. A no-op if the store does not implement
     * {@link SessionStore#delete} (e.g. append-only backends); whether subagent transcripts are
     * removed too depends on the store's cascade semantics.
     */
    public static void deleteSessionViaStore(SessionStore store, String sessionId, String directory)
            throws Exception {
        MetadataEntries.requireUuid(sessionId, "session_id");
        if (SessionStores.implementsMethod(store, "delete")) {
            store.delete(SessionKey.of(SessionPaths.projectKeyForDirectory(directory), sessionId));
        }
    }

    /** {@link #forkSessionViaStore(SessionStore, String, String, String, String)}: whole transcript, cwd. */
    public static ForkSessionResult forkSessionViaStore(SessionStore store, String sessionId) throws Exception {
        return forkSessionViaStore(store, sessionId, null, null, null);
    }

    /**
     * Store counterpart of {@link #forkSession(String, String, String, String)}: loads the source,
     * runs the fork transform and appends the result under a new key of the same store.
     *
     * @throws java.nio.file.NoSuchFileException if the store has no entries for the source session
     */
    public static ForkSessionResult forkSessionViaStore(SessionStore store, String sessionId, String directory,
                                                        String upToMessageId, String title) throws Exception {
        return new ForkSessionResult(SessionForker.forkInStore(store, sessionId, directory, upToMessageId, title));
    }

    /** {@link #importSessionToStore(String, SessionStore, String, boolean, int)} searching all project dirs. */
    public static void importSessionToStore(String sessionId, SessionStore store) throws Exception {
        importSessionToStore(sessionId, store, null, true, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES);
    }

    /** {@link #importSessionToStore(String, SessionStore, String, boolean, int)} with subagents, batches of 500. */
    public static void importSessionToStore(String sessionId, SessionStore store, String directory) throws Exception {
        importSessionToStore(sessionId, store, directory, true, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES);
    }

    /**
     * Replays a local session transcript into a store, e.g. to migrate sessions or to catch a
     * store up after a {@code MirrorErrorMessage}. Lines are appended in batches of
     * {@code batchSize} entries (or 1 MiB). The project key is the on-disk project directory name,
     * exactly what live mirroring would use; adapters should dedupe on {@code uuid}.
     *
     * @param includeSubagents also import {@code <sessionId>/subagents/**} transcripts and their
     *                         {@code .meta.json} sidecars (as {@code agent_metadata} entries)
     * @param batchSize        maximum entries per append; non-positive means 500
     * @throws java.nio.file.NoSuchFileException if the session file cannot be found
     */
    public static void importSessionToStore(String sessionId, SessionStore store, String directory,
                                            boolean includeSubagents, int batchSize) throws Exception {
        SessionImporter.importSession(sessionId, store, directory, includeSubagents, batchSize);
    }

    private static void appendStamped(SessionStore store, String sessionId, Map<String, Object> entry,
                                      String directory) throws Exception {
        String projectKey = SessionPaths.projectKeyForDirectory(directory);
        store.append(SessionKey.of(projectKey, sessionId), List.of(MetadataEntries.stamped(entry)));
    }
}
