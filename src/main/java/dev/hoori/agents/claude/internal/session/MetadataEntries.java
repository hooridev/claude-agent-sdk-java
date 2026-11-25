package dev.hoori.agents.claude.internal.session;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Validated construction of the typed metadata entries the SDK appends to sessions. */
public final class MetadataEntries {

    private MetadataEntries() {
    }

    /**
     * Returns {@code value} if it is a UUID.
     *
     * @throws IllegalArgumentException otherwise ({@code "Invalid <what>: <value>"})
     */
    public static String requireUuid(String value, String what) {
        if (SessionPaths.validateUuid(value) == null) {
            throw new IllegalArgumentException("Invalid " + what + ": " + value);
        }
        return value;
    }

    /** {@code {type:"custom-title", customTitle, sessionId}}; the title is stripped and must be non-empty. */
    public static Map<String, Object> customTitle(String sessionId, String title) {
        requireUuid(sessionId, "session_id");
        String stripped = title == null ? "" : title.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("title must be non-empty");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "custom-title");
        entry.put("customTitle", stripped);
        entry.put("sessionId", sessionId);
        return entry;
    }

    /**
     * {@code {type:"tag", tag, sessionId}}; the tag is Unicode-sanitized and stripped, {@code null}
     * clears the tag (stored as {@code ""}).
     */
    public static Map<String, Object> tag(String sessionId, String tag) {
        requireUuid(sessionId, "session_id");
        String value = "";
        if (tag != null) {
            value = UnicodeSanitizer.sanitize(tag).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("tag must be non-empty (use null to clear)");
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "tag");
        entry.put("tag", value);
        entry.put("sessionId", sessionId);
        return entry;
    }

    /** Adds a fresh {@code uuid} and the current {@code timestamp} (store entries carry both). */
    public static Map<String, Object> stamped(Map<String, Object> entry) {
        entry.put("uuid", UUID.randomUUID().toString());
        entry.put("timestamp", isoNow());
        return entry;
    }

    /** The current UTC time in ISO-8601 with milliseconds, like JS {@code toISOString()}. */
    public static String isoNow() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
