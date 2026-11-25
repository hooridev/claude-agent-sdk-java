package dev.hoori.agents.claude.internal.session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import dev.hoori.agents.claude.internal.Json;

/**
 * The fork transform shared by the disk and {@code SessionStore} paths (Python:
 * {@code _parse_fork_transcript} / {@code _build_fork_lines}): drops sidechains and progress
 * entries, remaps every UUID, rewrites {@code sessionId}, stamps {@code forkedFrom} and appends a
 * {@code custom-title} entry.
 */
public final class ForkTranscriptBuilder {

    private static final Set<String> TRANSCRIPT_TYPES = Set.of("user", "assistant", "attachment", "system", "progress");
    private static final List<String> LEAKED_FIELDS = List.of("teamName", "agentName", "slug", "sourceToolAssistantUUID");

    private ForkTranscriptBuilder() {
    }

    /** Transcript entries (typed, with a string {@code uuid}) plus the source's content replacements. */
    public record Parsed(List<Map<String, Object>> transcript, List<Object> contentReplacements) {
    }

    /** A built fork: the new session id and its entries in write order. */
    public record Fork(String sessionId, List<Map<String, Object>> entries) {
    }

    /** Parses JSONL bytes (invalid lines are skipped) and partitions them via {@link #partition}. */
    public static Parsed parseJsonl(byte[] content, String sessionId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String raw : new String(content, StandardCharsets.UTF_8).split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            try {
                entries.add(Json.parseObject(line));
            } catch (RuntimeException ignored) {
                // not a JSON object
            }
        }
        return partition(entries, sessionId);
    }

    /** Keeps transcript entries and collects {@code content-replacement} records of {@code sessionId}. */
    public static Parsed partition(List<Map<String, Object>> entries, String sessionId) {
        List<Map<String, Object>> transcript = new ArrayList<>();
        List<Object> replacements = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Object type = entry.get("type");
            if (TRANSCRIPT_TYPES.contains(type) && entry.get("uuid") instanceof String) {
                transcript.add(entry);
            } else if ("content-replacement".equals(type) && sessionId.equals(entry.get("sessionId"))
                    && entry.get("replacements") instanceof List<?> list) {
                replacements.addAll(list);
            }
        }
        return new Parsed(transcript, replacements);
    }

    /**
     * Builds the fork.
     *
     * @param upToMessageId inclusive cut-off message uuid, or {@code null} for the whole transcript
     * @param title         explicit title, or {@code null} to use {@code deriveTitle} + {@code " (fork)"}
     * @param deriveTitle   only invoked when no explicit title is given; may return {@code null}
     * @throws IllegalArgumentException if nothing is left to fork or {@code upToMessageId} is unknown
     */
    public static Fork build(Parsed parsed, String sessionId, String upToMessageId, String title,
                             Supplier<String> deriveTitle) {
        List<Map<String, Object>> transcript = parsed.transcript().stream()
                .filter(e -> !Json.isTrue(e, "isSidechain"))
                .collect(Collectors.toCollection(ArrayList::new));
        if (transcript.isEmpty()) {
            throw noMessages(sessionId);
        }
        if (upToMessageId != null && !upToMessageId.isEmpty()) {
            int cutoff = indexOfUuid(transcript, upToMessageId);
            if (cutoff < 0) {
                throw new IllegalArgumentException("Message " + upToMessageId + " not found in session " + sessionId);
            }
            transcript = new ArrayList<>(transcript.subList(0, cutoff + 1));
        }

        // Progress entries stay in the mapping (needed to walk the parent chain) but are not written.
        Map<String, String> uuidMapping = new HashMap<>();
        Map<String, Map<String, Object>> byUuid = new HashMap<>();
        for (Map<String, Object> entry : transcript) {
            String uuid = (String) entry.get("uuid");
            uuidMapping.put(uuid, UUID.randomUUID().toString());
            byUuid.put(uuid, entry);
        }
        List<Map<String, Object>> writable = transcript.stream()
                .filter(e -> !"progress".equals(e.get("type")))
                .toList();
        if (writable.isEmpty()) {
            throw noMessages(sessionId);
        }

        String forkedSessionId = UUID.randomUUID().toString();
        String now = MetadataEntries.isoNow();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < writable.size(); i++) {
            Map<String, Object> original = writable.get(i);
            String originalUuid = (String) original.get("uuid");
            Object logicalParent = original.get("logicalParentUuid");

            Map<String, Object> forked = Json.deepCopy(original);
            forked.put("uuid", uuidMapping.get(originalUuid));
            forked.put("parentUuid", resolveParent(original, byUuid, uuidMapping));
            forked.put("logicalParentUuid", isTruthy(logicalParent) ? uuidMapping.get(logicalParent) : logicalParent);
            forked.put("sessionId", forkedSessionId);
            // Only the last entry gets a fresh timestamp (leaf detection on resume).
            forked.put("timestamp", i == writable.size() - 1 ? now : original.getOrDefault("timestamp", now));
            forked.put("isSidechain", false);
            forked.put("forkedFrom", Json.obj("sessionId", sessionId, "messageUuid", originalUuid));
            LEAKED_FIELDS.forEach(forked::remove);
            out.add(forked);
        }

        if (!parsed.contentReplacements().isEmpty()) {
            Map<String, Object> replacement = new LinkedHashMap<>();
            replacement.put("type", "content-replacement");
            replacement.put("sessionId", forkedSessionId);
            replacement.put("replacements", Json.parse(Json.write(parsed.contentReplacements())));
            replacement.put("uuid", UUID.randomUUID().toString());
            replacement.put("timestamp", now);
            out.add(replacement);
        }

        String forkTitle = title != null ? title.strip() : "";
        if (forkTitle.isEmpty()) {
            String derived = deriveTitle.get();
            forkTitle = (derived == null || derived.isEmpty() ? "Forked session" : derived) + " (fork)";
        }
        Map<String, Object> titleEntry = new LinkedHashMap<>();
        titleEntry.put("type", "custom-title");
        titleEntry.put("sessionId", forkedSessionId);
        titleEntry.put("customTitle", forkTitle);
        titleEntry.put("uuid", UUID.randomUUID().toString());
        titleEntry.put("timestamp", now);
        out.add(titleEntry);

        return new Fork(forkedSessionId, out);
    }

    /** The new uuid of the nearest non-progress ancestor, or {@code null}. */
    private static String resolveParent(Map<String, Object> entry, Map<String, Map<String, Object>> byUuid,
                                        Map<String, String> uuidMapping) {
        Set<String> seen = new HashSet<>(); // guards against parent cycles in corrupt transcripts
        Object parentId = entry.get("parentUuid");
        while (parentId instanceof String id && !id.isEmpty() && seen.add(id)) {
            Map<String, Object> parent = byUuid.get(id);
            if (parent == null) {
                return null;
            }
            if (!"progress".equals(parent.get("type"))) {
                return uuidMapping.get(id);
            }
            parentId = parent.get("parentUuid");
        }
        return null;
    }

    /**
     * Title of a disk transcript: last {@code customTitle}, then last {@code aiTitle} (tail before
     * head), then the first prompt; {@code null} if none.
     */
    public static String deriveTitleFromBytes(byte[] content) {
        int length = content.length;
        String head = new String(content, 0, Math.min(length, SessionText.LITE_READ_BUF_SIZE), StandardCharsets.UTF_8);
        int tailStart = Math.max(0, length - SessionText.LITE_READ_BUF_SIZE);
        String tail = new String(content, tailStart, length - tailStart, StandardCharsets.UTF_8);
        return firstNonEmpty(
                SessionText.extractLastJsonStringField(tail, "customTitle"),
                SessionText.extractLastJsonStringField(head, "customTitle"),
                SessionText.extractLastJsonStringField(tail, "aiTitle"),
                SessionText.extractLastJsonStringField(head, "aiTitle"),
                SessionText.extractFirstPromptFromHead(head));
    }

    /** Same precedence as {@link #deriveTitleFromBytes} over already-parsed entries. */
    public static String deriveTitleFromEntries(List<Map<String, Object>> entries) {
        String custom = null;
        String ai = null;
        StringBuilder jsonl = new StringBuilder();
        for (Map<String, Object> entry : entries) {
            if (entry.get("customTitle") instanceof String s && !s.isEmpty()) {
                custom = s;
            }
            if (entry.get("aiTitle") instanceof String s && !s.isEmpty()) {
                ai = s;
            }
            jsonl.append(Json.write(entry)).append('\n');
        }
        return firstNonEmpty(custom, ai, SessionText.extractFirstPromptFromHead(jsonl.toString()));
    }

    private static int indexOfUuid(List<Map<String, Object>> transcript, String uuid) {
        for (int i = 0; i < transcript.size(); i++) {
            if (uuid.equals(transcript.get(i).get("uuid"))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTruthy(Object value) {
        return value != null && !(value instanceof String s && s.isEmpty()) && !Boolean.FALSE.equals(value);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static IllegalArgumentException noMessages(String sessionId) {
        return new IllegalArgumentException("Session " + sessionId + " has no messages to fork");
    }
}
