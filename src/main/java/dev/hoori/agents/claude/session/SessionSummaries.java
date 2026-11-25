package dev.hoori.agents.claude.session;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.session.SessionText;

/** Incrementally maintained session summaries for {@link SessionStore} adapters (Python: {@code session_summary}). */
public final class SessionSummaries {

    /** JSONL keys → summary data keys of last-wins string fields. */
    private static final Map<String, String> LAST_WINS_FIELDS = Map.of(
            "customTitle", "custom_title",
            "aiTitle", "ai_title",
            "lastPrompt", "last_prompt",
            "summary", "summary_hint",
            "gitBranch", "git_branch");

    private SessionSummaries() {
    }

    /**
     * Folds a batch of appended entries into the running summary of {@code key}. Call it from
     * {@link SessionStore#append} for main transcripts only ({@code key.subpath() == null}).
     * The returned {@code mtime} is {@code prev}'s (0 for a new session): stamp your storage write
     * time after persisting.
     *
     * @param prev the previous summary of the key, or {@code null} on the first append
     */
    public static SessionSummaryEntry fold(SessionSummaryEntry prev, SessionKey key,
                                           List<Map<String, Object>> entries) {
        String sessionId = prev != null ? prev.sessionId() : key.sessionId();
        long mtime = prev != null ? prev.mtime() : 0;
        Map<String, Object> data = prev != null ? new LinkedHashMap<>(prev.data()) : new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            Long ms = isoToEpochMillis(entry.get("timestamp"));
            if (!data.containsKey("is_sidechain")) {
                data.put("is_sidechain", Boolean.TRUE.equals(entry.get("isSidechain")));
            }
            if (!data.containsKey("created_at") && ms != null) {
                data.put("created_at", ms);
            }
            if (!data.containsKey("cwd") && entry.get("cwd") instanceof String cwd && !cwd.isEmpty()) {
                data.put("cwd", cwd);
            }
            foldFirstPrompt(data, entry);
            LAST_WINS_FIELDS.forEach((source, target) -> {
                if (entry.get(source) instanceof String value) {
                    data.put(target, value);
                }
            });
            if ("tag".equals(entry.get("type"))) {
                if (entry.get("tag") instanceof String tag && !tag.isEmpty()) {
                    data.put("tag", tag);
                } else {
                    data.remove("tag"); // an empty or absent tag clears it
                }
            }
        }
        return new SessionSummaryEntry(sessionId, mtime, data);
    }

    private static void foldFirstPrompt(Map<String, Object> data, Map<String, Object> entry) {
        if (Boolean.TRUE.equals(data.get("first_prompt_locked")) || !"user".equals(entry.get("type"))
                || Boolean.TRUE.equals(entry.get("isMeta")) || Boolean.TRUE.equals(entry.get("isCompactSummary"))) {
            return;
        }
        Map<String, Object> message = Json.object(entry, "message");
        if (message != null && message.get("content") instanceof List<?> blocks
                && blocks.stream().anyMatch(b -> b instanceof Map<?, ?> m && "tool_result".equals(m.get("type")))) {
            return;
        }
        for (String raw : SessionText.textsOf(message)) {
            String result = raw.replace("\n", " ").strip();
            if (result.isEmpty()) {
                continue;
            }
            Matcher command = SessionText.COMMAND_NAME_PATTERN.matcher(result);
            if (command.find()) {
                if (!(data.get("command_fallback") instanceof String s) || s.isEmpty()) {
                    data.put("command_fallback", command.group(1));
                }
                continue;
            }
            if (SessionText.SKIP_FIRST_PROMPT_PATTERN.matcher(result).lookingAt()) {
                continue;
            }
            data.put("first_prompt", SessionText.truncate(result, 200));
            data.put("first_prompt_locked", true);
            return;
        }
    }

    /**
     * Converts a summary to a {@link SessionInfo}.
     *
     * @return the info, or {@code null} for sidechain sessions or sessions without any summary text
     */
    public static SessionInfo toSessionInfo(SessionSummaryEntry entry, String projectPath) {
        Map<String, Object> data = entry.data();
        if (Boolean.TRUE.equals(data.get("is_sidechain"))) {
            return null;
        }
        String firstPrompt = nonEmpty(Boolean.TRUE.equals(data.get("first_prompt_locked"))
                ? Json.string(data, "first_prompt") : Json.string(data, "command_fallback"));
        String customTitle = firstNonEmpty(Json.string(data, "custom_title"), Json.string(data, "ai_title"));
        String summary = firstNonEmpty(customTitle, Json.string(data, "last_prompt"),
                Json.string(data, "summary_hint"), firstPrompt);
        if (summary == null) {
            return null;
        }
        return new SessionInfo(entry.sessionId(), summary, entry.mtime(), null, customTitle, firstPrompt,
                nonEmpty(Json.string(data, "git_branch")),
                firstNonEmpty(Json.string(data, "cwd"), projectPath),
                nonEmpty(Json.string(data, "tag")),
                Json.longValue(data, "created_at"));
    }

    /** Parses an ISO-8601 timestamp to epoch milliseconds; naive timestamps are local time. */
    public static Long isoToEpochMillis(Object timestamp) {
        if (!(timestamp instanceof String ts)) {
            return null;
        }
        try {
            return Instant.parse(ts).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try the other ISO forms
        }
        try {
            return OffsetDateTime.parse(ts).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // try the other ISO forms
        }
        try {
            return LocalDateTime.parse(ts).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
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
