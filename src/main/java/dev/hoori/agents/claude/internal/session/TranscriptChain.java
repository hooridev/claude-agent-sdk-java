package dev.hoori.agents.claude.internal.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionMessage;

/**
 * Rebuilds the conversation of a transcript from its {@code parentUuid} links and turns it into
 * {@link SessionMessage}s (Python: the chain helpers of {@code sessions.py}). Shared by the disk
 * and {@code SessionStore} read paths.
 */
final class TranscriptChain {

    /** Transcript entry types that carry {@code uuid} + {@code parentUuid} chain links. */
    private static final Set<String> TRANSCRIPT_ENTRY_TYPES = Set.of("user", "assistant", "progress", "system",
            "attachment");

    private TranscriptChain() {
    }

    /** Parses JSONL into transcript entries; skips corrupt lines, non-transcript types and entries without uuid. */
    static List<Map<String, Object>> parseEntries(String content) {
        List<Object> parsed = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Json.parseObject(trimmed));
            } catch (RuntimeException e) {
                // corrupt or non-object line
            }
        }
        return filterEntries(parsed);
    }

    /** Keeps the transcript message entries (with a string {@code uuid}) of already-parsed store entries. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> filterEntries(List<?> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map && map.get("type") instanceof String type
                    && TRANSCRIPT_ENTRY_TYPES.contains(type) && map.get("uuid") instanceof String) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /**
     * Finds the main-chain leaf and walks {@code parentUuid} back to the root; returns root → leaf
     * with the tool calls and results of parallel turns put back.
     *
     * <p>{@code logicalParentUuid} (compact boundaries) is intentionally not followed: the compact
     * summary replaces the earlier messages.
     */
    static List<Map<String, Object>> buildConversationChain(List<Map<String, Object>> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, Object>> byUuid = indexByUuid(entries);
        Map<String, Integer> entryIndex = new HashMap<>();
        Set<String> parentUuids = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            entryIndex.put(uuid(entry), i);
            String parent = parentUuid(entry);
            if (parent != null) {
                parentUuids.add(parent);
            }
        }

        List<Map<String, Object>> leaves = new ArrayList<>();
        for (Map<String, Object> terminal : entries) {
            if (parentUuids.contains(uuid(terminal))) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> cur = terminal; cur != null && seen.add(uuid(cur)); cur = parentOf(cur, byUuid)) {
                if (isUserOrAssistant(cur)) {
                    leaves.add(cur);
                    break;
                }
            }
        }
        if (leaves.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> mainLeaves = leaves.stream()
                .filter(l -> !truthy(l.get("isSidechain")) && !truthy(l.get("teamName")) && !truthy(l.get("isMeta")))
                .toList();
        Map<String, Object> leaf = latest(mainLeaves.isEmpty() ? leaves : mainLeaves, entryIndex);

        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> chain = walkToRoot(leaf, byUuid, seen);
        return ParallelToolResultRecovery.recover(byUuid, chain, seen);
    }

    /** Subagent transcripts are linear: the last user/assistant entry is the leaf. */
    static List<Map<String, Object>> buildSubagentChain(List<Map<String, Object>> entries) {
        Map<String, Object> leaf = null;
        for (int i = entries.size() - 1; i >= 0 && leaf == null; i--) {
            if (isUserOrAssistant(entries.get(i))) {
                leaf = entries.get(i);
            }
        }
        return leaf == null ? List.of() : walkToRoot(leaf, indexByUuid(entries), new HashSet<>());
    }

    /** Main-session messages: chain, visible entries only, then paging. */
    static List<SessionMessage> toSessionMessages(List<Map<String, Object>> entries, int limit, int offset) {
        List<SessionMessage> messages = buildConversationChain(entries).stream()
                .filter(TranscriptChain::isVisible)
                .map(e -> toSessionMessage(e, null, null))
                .toList();
        return Paging.page(messages, limit, offset);
    }

    /** Subagent messages; every message carries the same parent ids. */
    static List<SessionMessage> toSubagentMessages(List<Map<String, Object>> entries, int limit, int offset,
                                                   String parentToolUseId, String parentAgentId) {
        List<SessionMessage> messages = buildSubagentChain(entries).stream()
                .filter(TranscriptChain::isUserOrAssistant)
                .map(e -> toSessionMessage(e, parentToolUseId, parentAgentId))
                .toList();
        return Paging.page(messages, limit, offset);
    }

    // ---------------------------------------------------------------- helpers

    /** Last entry wins for duplicate uuids; iteration order is first occurrence (like a Python dict). */
    static Map<String, Map<String, Object>> indexByUuid(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            byUuid.put(uuid(entry), entry);
        }
        return byUuid;
    }

    private static List<Map<String, Object>> walkToRoot(Map<String, Object> leaf,
                                                        Map<String, Map<String, Object>> byUuid, Set<String> seen) {
        List<Map<String, Object>> chain = new ArrayList<>();
        for (Map<String, Object> cur = leaf; cur != null && seen.add(uuid(cur)); cur = parentOf(cur, byUuid)) {
            chain.add(cur);
        }
        Collections.reverse(chain);
        return chain;
    }

    private static Map<String, Object> latest(List<Map<String, Object>> candidates, Map<String, Integer> entryIndex) {
        Map<String, Object> best = candidates.getFirst();
        int bestIndex = entryIndex.getOrDefault(uuid(best), -1);
        for (Map<String, Object> cur : candidates.subList(1, candidates.size())) {
            int curIndex = entryIndex.getOrDefault(uuid(cur), -1);
            if (curIndex > bestIndex) {
                best = cur;
                bestIndex = curIndex;
            }
        }
        return best;
    }

    private static Map<String, Object> parentOf(Map<String, Object> entry, Map<String, Map<String, Object>> byUuid) {
        String parent = parentUuid(entry);
        return parent == null ? null : byUuid.get(parent);
    }

    private static boolean isVisible(Map<String, Object> entry) {
        return isUserOrAssistant(entry) && !truthy(entry.get("isMeta")) && !truthy(entry.get("isSidechain"))
                && !truthy(entry.get("teamName"));
    }

    private static boolean isUserOrAssistant(Map<String, Object> entry) {
        Object type = entry.get("type");
        return "user".equals(type) || "assistant".equals(type);
    }

    private static SessionMessage toSessionMessage(Map<String, Object> entry, String parentToolUseId,
                                                   String parentAgentId) {
        String sessionId = Json.string(entry, "sessionId");
        return new SessionMessage("user".equals(entry.get("type")) ? "user" : "assistant", uuid(entry),
                sessionId != null ? sessionId : "", entry.get("message"), parentToolUseId, parentAgentId);
    }

    static String uuid(Map<String, Object> entry) {
        return (String) entry.get("uuid");
    }

    /** The non-empty string {@code parentUuid}, or {@code null}. */
    static String parentUuid(Map<String, Object> entry) {
        return entry.get("parentUuid") instanceof String s && !s.isEmpty() ? s : null;
    }

    /** Python truthiness of a JSON value. */
    static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case String s -> !s.isEmpty();
            case Number n -> n.doubleValue() != 0;
            case Map<?, ?> m -> !m.isEmpty();
            case List<?> l -> !l.isEmpty();
            default -> true;
        };
    }
}
