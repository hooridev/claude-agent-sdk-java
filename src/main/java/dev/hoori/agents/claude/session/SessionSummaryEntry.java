package dev.hoori.agents.claude.session;

import java.util.Map;

/**
 * Incrementally maintained session summary. Stores obtain it from
 * {@link SessionSummaries#fold(SessionSummaryEntry, SessionKey, java.util.List)} inside
 * {@link SessionStore#append} and persist it verbatim.
 *
 * @param mtime storage write time in epoch milliseconds, from the same clock as
 *              {@link SessionStoreListEntry#mtime()}
 * @param data  opaque SDK-owned state; persist verbatim, do not interpret
 */
public record SessionSummaryEntry(String sessionId, long mtime, Map<String, Object> data) {
}
