package dev.hoori.agents.claude.session;

/**
 * Entry returned by {@link SessionStore#listSessions(String)}.
 *
 * @param mtime last-modified time in Unix epoch milliseconds
 */
public record SessionStoreListEntry(String sessionId, long mtime) {
}
