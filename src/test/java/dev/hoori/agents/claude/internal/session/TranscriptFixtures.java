package dev.hoori.agents.claude.internal.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/** Builders for transcript entries in the CLI's JSONL shape (mutable maps, so tests can tweak them). */
public final class TranscriptFixtures {

    private TranscriptFixtures() {
    }

    /** A transcript entry; {@code content} (if not null) becomes {@code message.content}; extras are key/value pairs. */
    public static Map<String, Object> entry(String type, String uuid, String parentUuid, String sessionId,
                                            Object content, Object... extras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("uuid", uuid);
        entry.put("parentUuid", parentUuid);
        entry.put("sessionId", sessionId);
        if (content != null) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", type.equals("assistant") ? "assistant" : "user");
            message.put("content", content);
            entry.put("message", message);
        }
        for (int i = 0; i < extras.length; i += 2) {
            entry.put((String) extras[i], extras[i + 1]);
        }
        return entry;
    }

    /** One assistant turn with parallel tool calls: calls and results, both in file order. */
    public record ParallelTurn(List<Map<String, Object>> calls, List<Map<String, Object>> results) {
    }

    /**
     * Matches what the CLI writes: one assistant entry per tool_use, all sharing {@code message.id}
     * and chained, and one tool_result per call whose parent is that call's entry.
     * {@code resultOrder} is the order the results are written (and timestamped) in.
     */
    @SuppressWarnings("unchecked")
    public static ParallelTurn parallelToolTurn(String sessionId, String parentUuid, int tools, int... resultOrder) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (int i = 0; i < tools; i++) {
            Map<String, Object> call = entry("assistant", "call-" + i,
                    calls.isEmpty() ? parentUuid : (String) calls.getLast().get("uuid"), sessionId,
                    List.of(Json.obj("type", "tool_use", "id", "toolu_" + i, "name", "Read", "input", Map.of())),
                    "timestamp", "2026-01-01T00:00:01.%03dZ".formatted(i));
            ((Map<String, Object>) call.get("message")).put("id", "msg_parallel");
            calls.add(call);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int position = 0; position < resultOrder.length; position++) {
            int i = resultOrder[position];
            results.add(entry("user", "result-" + i, "call-" + i, sessionId,
                    List.of(Json.obj("type", "tool_result", "tool_use_id", "toolu_" + i, "content", "ok")),
                    "sourceToolAssistantUUID", "call-" + i,
                    "timestamp", "2026-01-01T00:00:02.%03dZ".formatted(position)));
        }
        return new ParallelTurn(calls, results);
    }

    /** Entries as compact JSONL (the CLI's on-disk format). */
    public static String jsonl(List<Map<String, Object>> entries) {
        StringBuilder sb = new StringBuilder();
        entries.forEach(e -> sb.append(Json.write(e)).append('\n'));
        return sb.toString();
    }
}
