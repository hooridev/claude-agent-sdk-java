package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.TranscriptChain.parentUuid;
import static dev.hoori.agents.claude.internal.session.TranscriptChain.truthy;
import static dev.hoori.agents.claude.internal.session.TranscriptChain.uuid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.hoori.agents.claude.internal.Json;

/**
 * Puts back the tool calls and results a parallel turn left off the {@code parentUuid} chain
 * (Python: {@code _recover_orphaned_parallel_tool_results}).
 *
 * <p>The CLI writes one assistant entry per content block, so a response with N parallel tool
 * calls is N chained assistant entries sharing {@code message.id}. Each tool_result's parent is its
 * own call's entry, so the transcript forks and the walk keeps only the branch the next turn
 * continued from. For each response on the chain this collects its unreached assistant entries
 * and tool results and inserts them right after the response's last entry on the chain.
 */
final class ParallelToolResultRecovery {

    private static final Comparator<Map<String, Object>> BY_TIMESTAMP =
            Comparator.comparing(e -> e.get("timestamp") instanceof String s ? s : "");

    private ParallelToolResultRecovery() {
    }

    /**
     * @param byUuid all transcript entries by uuid
     * @param chain  the walked chain, root → leaf
     * @param seen   uuids on {@code chain}; recovered uuids are added
     */
    static List<Map<String, Object>> recover(Map<String, Map<String, Object>> byUuid,
                                             List<Map<String, Object>> chain, Set<String> seen) {
        List<Map<String, Object>> chainAssistants = chain.stream()
                .filter(e -> "assistant".equals(e.get("type"))).toList();
        // Anchor = the last on-chain entry of each response.
        Map<String, Map<String, Object>> anchorByMessageId = new HashMap<>();
        for (Map<String, Object> entry : chainAssistants) {
            String messageId = assistantMessageId(entry);
            if (messageId != null) {
                anchorByMessageId.put(messageId, entry);
            }
        }
        if (anchorByMessageId.isEmpty()) {
            return chain;
        }

        // Group assistant entries by response and record which entry made each call. A tool_use id
        // claimed by two different responses is ambiguous (null) and never used to place a result.
        Map<String, List<Map<String, Object>>> siblingsByMessageId = new HashMap<>();
        Map<String, Map<String, Object>> callOwner = new HashMap<>();
        List<Map<String, Object>> toolResultEntries = new ArrayList<>();
        for (Map<String, Object> entry : byUuid.values()) {
            String messageId = assistantMessageId(entry);
            if (messageId != null) {
                siblingsByMessageId.computeIfAbsent(messageId, k -> new ArrayList<>()).add(entry);
                for (String callId : contentBlockIds(entry, "tool_use", "id")) {
                    if (!callOwner.containsKey(callId)) {
                        callOwner.put(callId, entry);
                        continue;
                    }
                    Map<String, Object> owner = callOwner.get(callId);
                    boolean sameResponse = owner != null && messageId.equals(assistantMessageId(owner));
                    callOwner.put(callId, sameResponse ? entry : null);
                }
            } else if (isToolResultEntry(entry)) {
                toolResultEntries.add(entry);
            }
        }

        Map<String, List<Map<String, Object>>> resultsByAssistant = indexResults(byUuid, callOwner, toolResultEntries);

        Set<String> answeredOnChain = null; // only needed to place strays (rare): built on first use
        Map<String, Integer> filePosition = null;
        Set<String> processed = new HashSet<>();
        Map<String, List<Map<String, Object>>> inserts = new HashMap<>();
        for (Map<String, Object> assistant : chainAssistants) {
            String messageId = assistantMessageId(assistant);
            if (messageId == null || !processed.add(messageId)) {
                continue;
            }
            List<Map<String, Object>> group = siblingsByMessageId.getOrDefault(messageId, List.of(assistant));
            Set<String> memberUuids = new HashSet<>();
            group.forEach(m -> memberUuids.add(uuid(m)));
            List<Map<String, Object>> orphanedSiblings = new ArrayList<>(
                    group.stream().filter(m -> !seen.contains(uuid(m))).toList());
            List<Map<String, Object>> orphanedResults = new ArrayList<>();
            // Results indexed under this response whose parent is not one of its entries: recovered
            // only if they answer a still-unanswered call.
            List<Map<String, Object>> strays = new ArrayList<>();
            Set<String> collected = new HashSet<>();
            for (Map<String, Object> member : group) {
                for (Map<String, Object> result : resultsByAssistant.getOrDefault(uuid(member), List.of())) {
                    if (seen.contains(uuid(result)) || !collected.add(uuid(result))) {
                        continue;
                    }
                    (memberUuids.contains(parentUuid(result)) ? orphanedResults : strays).add(result);
                }
            }

            if (!strays.isEmpty()) {
                if (answeredOnChain == null) {
                    answeredOnChain = new HashSet<>();
                    for (Map<String, Object> entry : chain) {
                        answeredOnChain.addAll(contentBlockIds(entry, "tool_result", "tool_use_id"));
                    }
                    filePosition = new HashMap<>();
                    for (String uid : byUuid.keySet()) {
                        filePosition.put(uid, filePosition.size());
                    }
                }
                Set<String> answered = new HashSet<>(answeredOnChain);
                orphanedResults.forEach(r -> answered.addAll(contentBlockIds(r, "tool_result", "tool_use_id")));
                Map<String, Integer> positions = filePosition;
                strays.sort(Comparator.comparingInt(e -> positions.get(uuid(e))));
                for (Map<String, Object> result : strays) {
                    List<String> callIds = contentBlockIds(result, "tool_result", "tool_use_id");
                    boolean answersOpenCall = callIds.stream().anyMatch(callId -> !answered.contains(callId)
                            && callOwner.get(callId) != null && memberUuids.contains(uuid(callOwner.get(callId))));
                    if (answersOpenCall) {
                        answered.addAll(callIds);
                        orphanedResults.add(result);
                    }
                }
            }

            if (orphanedSiblings.isEmpty() && orphanedResults.isEmpty()) {
                continue;
            }
            // Stable timestamp sort keeps content-block and completion order; ties keep their order.
            orphanedSiblings.sort(BY_TIMESTAMP);
            orphanedResults.sort(BY_TIMESTAMP);
            List<Map<String, Object>> recovered = new ArrayList<>(orphanedSiblings);
            recovered.addAll(orphanedResults);
            recovered.forEach(e -> seen.add(uuid(e)));
            inserts.put(uuid(anchorByMessageId.get(messageId)), recovered);
        }

        if (inserts.isEmpty()) {
            return chain;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> entry : chain) {
            result.add(entry);
            result.addAll(inserts.getOrDefault(uuid(entry), List.of()));
        }
        return result;
    }

    /**
     * Indexes each tool result under the assistant entries it may belong to: its parent, its
     * {@code sourceToolAssistantUUID}, and the owner of each call it answers (the last two only
     * when written by the same agent).
     */
    private static Map<String, List<Map<String, Object>>> indexResults(Map<String, Map<String, Object>> byUuid,
                                                                       Map<String, Map<String, Object>> callOwner,
                                                                       List<Map<String, Object>> toolResults) {
        Map<String, List<Map<String, Object>>> index = new LinkedHashMap<>();
        for (Map<String, Object> result : toolResults) {
            index.computeIfAbsent(parentUuid(result), k -> new ArrayList<>()).add(result);
            if (result.get("sourceToolAssistantUUID") instanceof String source) {
                Map<String, Object> sourceEntry = byUuid.get(source);
                if (sourceEntry != null && sameWriter(result, sourceEntry)) {
                    index.computeIfAbsent(source, k -> new ArrayList<>()).add(result);
                }
            }
            for (String callId : contentBlockIds(result, "tool_result", "tool_use_id")) {
                Map<String, Object> owner = callOwner.get(callId);
                if (owner != null && sameWriter(result, owner)) {
                    index.computeIfAbsent(uuid(owner), k -> new ArrayList<>()).add(result);
                }
            }
        }
        return index;
    }

    /** The API {@code message.id} of an assistant entry, or {@code null}. */
    private static String assistantMessageId(Map<String, Object> entry) {
        if (!"assistant".equals(entry.get("type"))) {
            return null;
        }
        String id = Json.string(Json.object(entry, "message"), "id");
        return id != null && !id.isEmpty() ? id : null;
    }

    /** {@code idField} of each {@code blockType} content block of an entry's message. */
    private static List<String> contentBlockIds(Map<String, Object> entry, String blockType, String idField) {
        Map<String, Object> message = Json.object(entry, "message");
        if (message == null || !(message.get("content") instanceof List<?> content)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object block : content) {
            if (block instanceof Map<?, ?> b && blockType.equals(b.get("type")) && b.get(idField) instanceof String id) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** A user entry with a parent whose content carries a tool_result block. */
    private static boolean isToolResultEntry(Map<String, Object> entry) {
        if (!"user".equals(entry.get("type")) || parentUuid(entry) == null) {
            return false;
        }
        Map<String, Object> message = Json.object(entry, "message");
        return message != null && message.get("content") instanceof List<?> content
                && content.stream().anyMatch(b -> b instanceof Map<?, ?> m && "tool_result".equals(m.get("type")));
    }

    private static boolean sameWriter(Map<String, Object> a, Map<String, Object> b) {
        return truthy(a.get("isSidechain")) == truthy(b.get("isSidechain"))
                && Objects.equals(a.get("agentId"), b.get("agentId"));
    }
}
