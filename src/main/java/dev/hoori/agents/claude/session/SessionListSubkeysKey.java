package dev.hoori.agents.claude.session;

/** Key argument to {@link SessionStore#listSubkeys(SessionListSubkeysKey)} (no subpath). */
public record SessionListSubkeysKey(String projectKey, String sessionId) {
}
