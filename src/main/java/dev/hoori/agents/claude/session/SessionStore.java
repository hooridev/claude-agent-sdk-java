package dev.hoori.agents.claude.session;

import java.util.List;
import java.util.Map;

/**
 * Adapter for mirroring session transcripts to external storage.
 *
 * <p>The CLI still writes to local disk; the adapter receives a secondary copy. Only
 * {@link #append} and {@link #load} are required. The other methods are optional: leave them
 * un-overridden and the SDK treats them as absent (it checks whether your class overrides them).
 *
 * <p>Entries are JSON-safe maps, one per JSONL transcript line. Most carry a stable {@code uuid}
 * that adapters should treat as an idempotency key.
 */
public interface SessionStore {

    /**
     * Mirrors a batch of transcript entries. Called after the CLI's local write succeeded.
     * Failures are retried (3 attempts total) before the batch is dropped and surfaced as a
     * {@code MirrorErrorMessage}.
     */
    void append(SessionKey key, List<Map<String, Object>> entries) throws Exception;

    /**
     * Loads a full session (for resume and the {@code *FromStore} helpers).
     *
     * @return the entries, or {@code null} for a key that was never written
     */
    List<Map<String, Object>> load(SessionKey key) throws Exception;

    /** Optional: lists sessions of a project; order is unspecified. */
    default List<SessionStoreListEntry> listSessions(String projectKey) throws Exception {
        throw new UnsupportedOperationException("listSessions");
    }

    /** Optional: returns the summaries maintained in {@link #append} for a project. */
    default List<SessionSummaryEntry> listSessionSummaries(String projectKey) throws Exception {
        throw new UnsupportedOperationException("listSessionSummaries");
    }

    /**
     * Optional: deletes a session. Deleting a main key (no subpath) must cascade to all its
     * subkeys. If unimplemented, deletion is a no-op.
     */
    default void delete(SessionKey key) throws Exception {
        throw new UnsupportedOperationException("delete");
    }

    /** Optional: lists all subpaths under a session (e.g. subagent transcripts). */
    default List<String> listSubkeys(SessionListSubkeysKey key) throws Exception {
        throw new UnsupportedOperationException("listSubkeys");
    }
}
