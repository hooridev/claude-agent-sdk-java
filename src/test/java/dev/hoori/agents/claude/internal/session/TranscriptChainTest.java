package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.TranscriptFixtures.parallelToolTurn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.session.TranscriptFixtures.ParallelTurn;
import dev.hoori.agents.claude.session.SessionMessage;

class TranscriptChainTest {

    private static Map<String, Object> node(String type, String uuid, String parent, Object... extras) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("uuid", uuid);
        entry.put("parentUuid", parent);
        for (int i = 0; i < extras.length; i += 2) {
            entry.put((String) extras[i], extras[i + 1]);
        }
        return entry;
    }

    @SafeVarargs
    private static List<Map<String, Object>> concat(List<Map<String, Object>>... parts) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (List<Map<String, Object>> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static List<String> uuids(List<Map<String, Object>> chain) {
        return chain.stream().map(e -> (String) e.get("uuid")).toList();
    }

    private static List<String> chainOf(List<Map<String, Object>> entries) {
        return uuids(TranscriptChain.buildConversationChain(entries));
    }

    @Test
    void emptySingleAndLinear() {
        assertEquals(List.of(), TranscriptChain.buildConversationChain(List.of()));
        assertEquals(List.of("a"), chainOf(List.of(node("user", "a", null))));
        assertEquals(List.of("a", "b", "c"),
                chainOf(List.of(node("user", "a", null), node("assistant", "b", "a"), node("user", "c", "b"))));
    }

    @Test
    void onlyProgressEntriesGiveEmptyChain() {
        assertEquals(List.of(), chainOf(List.of(node("progress", "a", null), node("progress", "b", "a"))));
    }

    @Test
    void prefersMainLeafOverSidechainAndLatestByFilePosition() {
        assertEquals(List.of("root", "main"), chainOf(List.of(node("user", "root", null),
                node("assistant", "main", "root"), node("assistant", "side", "root", "isSidechain", true))));
        assertEquals(List.of("root", "new"), chainOf(List.of(node("user", "root", null),
                node("assistant", "old", "root"), node("assistant", "new", "root"))));
    }

    @Test
    void terminalNonMessageIsWalkedBackAndCyclesTerminate() {
        assertEquals(List.of("u", "a"), chainOf(List.of(node("user", "u", null), node("assistant", "a", "u"),
                node("progress", "p", "a"))));
        // a <-> b cycle plus a terminal leaf hanging off it
        List<String> chain = chainOf(List.of(node("user", "a", "b"), node("assistant", "b", "a"),
                node("user", "c", "b")));
        assertEquals("c", chain.getLast());
        assertEquals(3, chain.size());
    }

    @Test
    void recoversSiblingCallsDroppedByTheWalk() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 3, 1, 2, 0);
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(node("assistant", "answer", "result-0")));
        // Off-branch calls and their results go right after the last on-branch call.
        assertEquals(List.of("prompt", "call-0", "call-1", "call-2", "result-1", "result-2", "result-0", "answer"),
                chainOf(entries));
    }

    @Test
    void recoveredResultsAreInCompletionOrder() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 3, 1, 0, 2);
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(node("assistant", "answer", "result-2")));
        assertEquals(List.of("prompt", "call-0", "call-1", "call-2", "result-1", "result-0", "result-2", "answer"),
                chainOf(entries));
    }

    @Test
    void recoveredCallsAreInTimestampOrder() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 3, 1, 2, 0);
        List<Map<String, Object>> calls = turn.calls();
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)),
                List.of(calls.get(0), calls.get(2), calls.get(1)), turn.results(),
                List.of(node("assistant", "answer", "result-0")));
        assertEquals(List.of("call-0", "call-1", "call-2"), chainOf(entries).subList(1, 4));
    }

    @Test
    void sequentialToolCallsAreUnchanged() {
        List<Map<String, Object>> entries = List.of(
                node("user", "prompt", null),
                node("assistant", "call-0", "prompt", "message",
                        Json.obj("id", "msg_1", "content", List.of(Json.obj("type", "tool_use", "id", "toolu_0")))),
                node("user", "result-0", "call-0", "message",
                        Json.obj("content", List.of(Json.obj("type", "tool_result", "tool_use_id", "toolu_0")))),
                node("assistant", "call-1", "result-0", "message",
                        Json.obj("id", "msg_1", "content", List.of(Json.obj("type", "tool_use", "id", "toolu_1")))),
                node("user", "result-1", "call-1", "message",
                        Json.obj("content", List.of(Json.obj("type", "tool_result", "tool_use_id", "toolu_1")))));
        assertEquals(entries, TranscriptChain.buildConversationChain(entries));
    }

    @Test
    void abandonedBranchIsNotRecovered() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 2, 0, 1);
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null),
                node("assistant", "old-call", "prompt", "message", Json.obj("id", "msg_abandoned",
                        "content", List.of(Json.obj("type", "tool_use", "id", "toolu_old")))),
                node("user", "old-result", "old-call", "message",
                        Json.obj("content", List.of(Json.obj("type", "tool_result", "tool_use_id", "toolu_old"))))),
                turn.calls(), turn.results());
        assertEquals(List.of("prompt", "call-0", "call-1", "result-0", "result-1"), chainOf(entries));
    }

    @Test
    void recoversResultWhoseParentIsOutsideTheTurn() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 2, 0, 1);
        turn.results().get(0).put("parentUuid", "missing");
        turn.results().get(0).remove("sourceToolAssistantUUID");
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(node("assistant", "answer", "result-1")));
        assertEquals(List.of("prompt", "call-0", "call-1", "result-0", "result-1", "answer"), chainOf(entries));
    }

    @Test
    void doesNotDuplicateAnAnsweredCall() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 2, 0, 1);
        Map<String, Object> duplicate = new LinkedHashMap<>(turn.results().get(1));
        duplicate.put("uuid", "result-1-copy");
        duplicate.put("parentUuid", "elsewhere");
        duplicate.remove("sourceToolAssistantUUID");
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(duplicate, node("assistant", "answer", "result-1")));
        assertEquals(List.of("prompt", "call-0", "call-1", "result-0", "result-1", "answer"), chainOf(entries));
    }

    @Test
    void doesNotPullInAnotherAgentsResult() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 2, 0, 1);
        Map<String, Object> foreign = turn.results().get(0);
        foreign.put("parentUuid", "sidechain-call");
        foreign.put("isSidechain", true);
        foreign.put("agentId", "agent-1");
        foreign.remove("sourceToolAssistantUUID");
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(node("assistant", "answer", "result-1")));
        assertEquals(List.of("prompt", "call-0", "call-1", "result-1", "answer"), chainOf(entries));
    }

    @Test
    void recoveryToleratesMalformedEntries() {
        ParallelTurn turn = parallelToolTurn("s", "prompt", 2, 0, 1);
        turn.results().get(0).put("timestamp", 12345);
        List<Object> oddContent = new ArrayList<>(Arrays.asList("text", null, Json.obj("type", "tool_use")));
        List<Map<String, Object>> entries = concat(List.of(node("user", "prompt", null)), turn.calls(),
                turn.results(), List.of(
                        node("assistant", "no-message", "result-1"),
                        node("assistant", "odd", "no-message", "message", Json.obj("id", 7, "content", oddContent)),
                        node("user", "odd-result", "odd", "message",
                                Json.obj("content", List.of(Json.obj("type", "tool_result", "tool_use_id", 3))))));
        assertEquals(List.of("prompt", "call-0", "call-1", "result-0", "result-1", "no-message", "odd", "odd-result"),
                chainOf(entries));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,1,2", "2,1,0", "1,2,0", "0,2,1"})
    void parallelToolCallsKeepEveryCallAndResult(String order) {
        int[] resultOrder = Arrays.stream(order.split(",")).mapToInt(Integer::parseInt).toArray();
        ParallelTurn turn = parallelToolTurn("sid", "prompt", 3, resultOrder);
        String lastResult = (String) turn.results().getLast().get("uuid");
        List<Map<String, Object>> entries = concat(
                List.of(TranscriptFixtures.entry("user", "prompt", null, "sid", "read them")),
                turn.calls(), turn.results(),
                List.of(TranscriptFixtures.entry("assistant", "answer", lastResult, "sid", "done")));

        List<SessionMessage> messages = TranscriptChain.toSessionMessages(entries, 0, 0);

        assertEquals(List.of("toolu_0", "toolu_1", "toolu_2"), blockIds(messages, "tool_use", "id"));
        assertEquals(List.of("toolu_0", "toolu_1", "toolu_2"),
                blockIds(messages, "tool_result", "tool_use_id").stream().sorted().toList());
        assertEquals(8, messages.stream().map(SessionMessage::uuid).distinct().count());
        assertEquals(8, messages.size());
        assertEquals("prompt", messages.getFirst().uuid());
        assertEquals(List.of("assistant", "assistant", "assistant", "user", "user", "user"),
                messages.subList(1, 7).stream().map(SessionMessage::type).toList());
        assertEquals("answer", messages.getLast().uuid());
    }

    @Test
    void parallelTurnAsLastTurnAndPagingCountsRecoveredEntries() {
        ParallelTurn turn = parallelToolTurn("sid", "prompt", 2, 1, 0);
        List<Map<String, Object>> entries = concat(
                List.of(TranscriptFixtures.entry("user", "prompt", null, "sid", "read them")),
                turn.calls(), turn.results());
        assertEquals(List.of("prompt", "call-0", "call-1", "result-1", "result-0"),
                TranscriptChain.toSessionMessages(entries, 0, 0).stream().map(SessionMessage::uuid).toList());

        ParallelTurn turn3 = parallelToolTurn("sid", "prompt", 3, 0, 1, 2);
        List<Map<String, Object>> entries3 = concat(
                List.of(TranscriptFixtures.entry("user", "prompt", null, "sid", "read them")),
                turn3.calls(), turn3.results(),
                List.of(TranscriptFixtures.entry("assistant", "answer", "result-2", "sid", "k")));
        assertEquals(List.of("result-0", "result-1", "result-2"),
                TranscriptChain.toSessionMessages(entries3, 3, 4).stream().map(SessionMessage::uuid).toList());
    }

    @Test
    void visibleMessagesExcludeMetaSidechainAndTeamButKeepCompactSummary() {
        List<Map<String, Object>> entries = List.of(
                TranscriptFixtures.entry("user", "u1", null, "s", "hi"),
                TranscriptFixtures.entry("user", "meta", "u1", "s", "meta", "isMeta", true),
                TranscriptFixtures.entry("system", "sys", "meta", "s", null),
                TranscriptFixtures.entry("user", "compact", "sys", "s", "summary", "isCompactSummary", true),
                TranscriptFixtures.entry("assistant", "a1", "compact", "s", "ok"));
        assertEquals(List.of("u1", "compact", "a1"),
                TranscriptChain.toSessionMessages(entries, 0, 0).stream().map(SessionMessage::uuid).toList());
    }

    @Test
    void parseEntriesSkipsCorruptAndNonTranscriptLines() {
        String content = "not json\n" + "[1,2]\n"
                + Json.write(TranscriptFixtures.entry("user", "u1", null, "s", "hi")) + "\n"
                + "{\"type\":\"summary\",\"summary\":\"x\"}\n"
                + "{\"type\":\"custom-title\",\"uuid\":\"t\",\"customTitle\":\"x\"}\n"
                + "{\"type\":\"user\",\"uuid\":5}\n\n"
                + Json.write(TranscriptFixtures.entry("assistant", "a1", "u1", "s", "ok"));
        assertEquals(List.of("u1", "a1"), uuids(TranscriptChain.parseEntries(content)));
    }

    @Test
    void subagentChainUsesLastMessageAsLeafAndStampsParentIds() {
        List<Map<String, Object>> entries = List.of(
                TranscriptFixtures.entry("user", "u1", null, "s", "task"),
                TranscriptFixtures.entry("assistant", "a1", "u1", "s", "working"),
                TranscriptFixtures.entry("progress", "p", "a1", "s", null));
        List<SessionMessage> messages = TranscriptChain.toSubagentMessages(entries, 0, 0, "toolu_1", "parent");
        assertEquals(List.of("u1", "a1"), messages.stream().map(SessionMessage::uuid).toList());
        assertTrue(messages.stream().allMatch(m -> "toolu_1".equals(m.parentToolUseId())
                && "parent".equals(m.parentAgentId())));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> blockIds(List<SessionMessage> messages, String blockType, String field) {
        List<Object> ids = new ArrayList<>();
        for (SessionMessage m : messages) {
            if (((Map<String, Object>) m.message()).get("content") instanceof List<?> blocks) {
                for (Object block : blocks) {
                    Map<String, Object> b = (Map<String, Object>) block;
                    if (blockType.equals(b.get("type"))) {
                        ids.add(b.get(field));
                    }
                }
            }
        }
        return ids;
    }
}
