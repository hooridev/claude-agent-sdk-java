package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.SessionText.extractJsonStringField;
import static dev.hoori.agents.claude.internal.session.SessionText.extractLastJsonStringField;

import dev.hoori.agents.claude.internal.session.SessionText.LiteSessionFile;
import dev.hoori.agents.claude.session.SessionInfo;
import dev.hoori.agents.claude.session.SessionSummaries;

/**
 * Derives {@link SessionInfo} from the head and tail of a session JSONL without a full parse
 * (Python: {@code _parse_session_info_from_lite}). Shared by the disk and store paths.
 */
final class LiteSessionInfoParser {

    private LiteSessionInfoParser() {
    }

    /**
     * @return the info, or {@code null} for sidechain sessions and metadata-only sessions without
     *         any title, summary or prompt
     */
    static SessionInfo parse(String sessionId, LiteSessionFile lite, String projectPath) {
        String head = lite.head();
        String tail = lite.tail();
        int firstNewline = head.indexOf('\n');
        String firstLine = firstNewline >= 0 ? head.substring(0, firstNewline) : head;
        if (firstLine.contains("\"isSidechain\":true") || firstLine.contains("\"isSidechain\": true")) {
            return null;
        }

        // User-set title wins over the AI title; the head covers short sessions.
        String customTitle = firstNonEmpty(
                extractLastJsonStringField(tail, "customTitle"), extractLastJsonStringField(head, "customTitle"),
                extractLastJsonStringField(tail, "aiTitle"), extractLastJsonStringField(head, "aiTitle"));
        String firstPrompt = firstNonEmpty(SessionText.extractFirstPromptFromHead(head));
        String summary = firstNonEmpty(customTitle, extractLastJsonStringField(tail, "lastPrompt"),
                extractLastJsonStringField(tail, "summary"), firstPrompt);
        if (summary == null) {
            return null;
        }
        String gitBranch = firstNonEmpty(extractLastJsonStringField(tail, "gitBranch"),
                extractJsonStringField(head, "gitBranch"));
        String cwd = firstNonEmpty(extractJsonStringField(head, "cwd"), projectPath);
        // created_at from the first timestamp anywhere in the head: the first record may be a
        // metadata-only entry without one.
        Long createdAt = SessionSummaries.isoToEpochMillis(extractJsonStringField(head, "timestamp"));
        return new SessionInfo(sessionId, summary, lite.mtime(), lite.size(), customTitle, firstPrompt, gitBranch,
                cwd, extractTag(tail), createdAt);
    }

    /**
     * The tag of the last {@code {"type":"tag",...}} line. Scoped to those lines because a bare scan for
     * {@code "tag"} would match tool_use inputs (git tags, Docker tags, ...).
     */
    private static String extractTag(String tail) {
        String[] lines = tail.split("\n", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].startsWith("{\"type\":\"tag\"")) {
                return firstNonEmpty(extractLastJsonStringField(lines[i], "tag"));
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
