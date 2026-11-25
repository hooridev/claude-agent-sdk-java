package dev.hoori.agents.claude.session;

import java.util.Map;
import java.util.Objects;

import dev.hoori.agents.claude.internal.Json;

/**
 * Identifies a session transcript (or a subagent transcript) in a {@link SessionStore}.
 *
 * @param projectKey caller-defined scope; default is the sanitized cwd
 * @param sessionId  the session id
 * @param subpath    {@code null} for the main transcript, e.g. {@code subagents/agent-<id>} for subagents.
 *                   An empty string is invalid.
 */
public record SessionKey(String projectKey, String sessionId, String subpath) {

    public SessionKey {
        Objects.requireNonNull(projectKey, "projectKey");
        Objects.requireNonNull(sessionId, "sessionId");
        if (subpath != null && subpath.isEmpty()) {
            throw new IllegalArgumentException("subpath must be null (main transcript) or non-empty");
        }
    }

    /** Key of a main transcript. */
    public static SessionKey of(String projectKey, String sessionId) {
        return new SessionKey(projectKey, sessionId, null);
    }

    /** The wire/JSON form ({@code project_key}, {@code session_id}, {@code subpath}). */
    public Map<String, Object> toMap() {
        return Json.obj("project_key", projectKey, "session_id", sessionId, "subpath", subpath);
    }
}
