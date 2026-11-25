package dev.hoori.agents.claude.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.internal.Json;

/** {@link SessionSummaries#fold} and {@link SessionSummaries#toSessionInfo}. */
class SessionSummariesTest {

    private static final SessionKey KEY = SessionKey.of("proj", "11111111-1111-4111-8111-111111111111");

    private static Map<String, Object> user(Object content, Object... extras) {
        Map<String, Object> entry = Json.obj("type", "user", "timestamp", "2024-01-01T00:00:00.000Z",
                "message", Json.obj("role", "user", "content", content));
        entry.putAll(Json.obj(extras));
        return entry;
    }

    private static SessionSummaryEntry fold(SessionSummaryEntry prev, List<Map<String, Object>> entries) {
        return SessionSummaries.fold(prev, KEY, entries);
    }

    @Test
    void initFromNull() {
        assertEquals(new SessionSummaryEntry(KEY.sessionId(), 0, Map.of()), fold(null, List.of()));
    }

    @Test
    void setOnceFieldsFreeze() {
        SessionSummaryEntry s = fold(null, List.of(
                Json.obj("type", "x", "timestamp", "2024-01-01T00:00:00.000Z", "cwd", "/a", "isSidechain", false),
                Json.obj("type", "x", "timestamp", "2024-01-01T00:00:05.000Z", "cwd", "/b")));
        assertEquals(1704067200000L, s.data().get("created_at"));
        assertEquals("/a", s.data().get("cwd"));
        assertEquals(false, s.data().get("is_sidechain"));
        SessionSummaryEntry s2 = fold(s, List.of(
                Json.obj("type", "x", "timestamp", "2024-01-02T00:00:00.000Z", "cwd", "/c", "isSidechain", true)));
        assertEquals(1704067200000L, s2.data().get("created_at"));
        assertEquals("/a", s2.data().get("cwd"));
        assertEquals(false, s2.data().get("is_sidechain"));
    }

    @Test
    void lastWinsFieldsOverwrite() {
        SessionSummaryEntry s = fold(null, List.of(Json.obj("type", "x", "customTitle", "t1", "gitBranch", "main"),
                Json.obj("type", "x", "customTitle", "t2")));
        assertEquals("t2", s.data().get("custom_title"));
        assertEquals("main", s.data().get("git_branch"));
        SessionSummaryEntry s2 = fold(s, List.of(Json.obj("type", "x", "aiTitle", "ai", "lastPrompt", "lp",
                "summary", "sm", "gitBranch", "dev")));
        assertEquals("t2", s2.data().get("custom_title"));
        assertEquals("ai", s2.data().get("ai_title"));
        assertEquals("lp", s2.data().get("last_prompt"));
        assertEquals("sm", s2.data().get("summary_hint"));
        assertEquals("dev", s2.data().get("git_branch"));
    }

    @Test
    void mtimeIsNotDerivedFromEntriesAndPrevIsNotMutated() {
        assertEquals(0, fold(null, List.of(Json.obj("type", "x", "timestamp", "2024-01-01T00:00:05.000Z"))).mtime());
        SessionSummaryEntry prev = new SessionSummaryEntry("a", 42, new LinkedHashMap<>());
        SessionSummaryEntry next = fold(prev, List.of(Json.obj("type", "x", "customTitle", "t",
                "timestamp", "2024-01-01T00:00:10.000Z")));
        assertEquals(42, next.mtime());
        assertEquals("a", next.sessionId());
        assertEquals(Map.of(), prev.data());
    }

    @Test
    void tagSetClearAndScope() {
        SessionSummaryEntry s = fold(null, List.of(Json.obj("type", "tag", "tag", "wip")));
        assertEquals("wip", s.data().get("tag"));
        assertFalse(fold(s, List.of(Json.obj("type", "tag", "tag", ""))).data().containsKey("tag"));
        assertEquals("wip", fold(s, List.of(Json.obj("type", "user", "tag", "ignored"))).data().get("tag"));
    }

    @Test
    void sidechainLatchesOnFirstEntryEvenWithoutTimestamp() {
        SessionSummaryEntry s = fold(null, List.of(Json.obj("type", "user", "isSidechain", true),
                Json.obj("type", "x", "timestamp", "2024-01-01T00:00:00Z")));
        assertEquals(true, s.data().get("is_sidechain"));
        assertEquals(1704067200000L, s.data().get("created_at"));
    }

    @Test
    void firstPromptRules() {
        SessionSummaryEntry s = fold(null, List.of(user("ignored meta", "isMeta", true),
                user("ignored compact", "isCompactSummary", true),
                user(List.of(Json.obj("type", "tool_result", "tool_use_id", "x", "content", "res"))),
                user("<local-command-stdout> out"),
                user("real first"), user("not me")));
        assertEquals("real first", s.data().get("first_prompt"));
        assertEquals(true, s.data().get("first_prompt_locked"));

        SessionSummaryEntry cmd = fold(null, List.of(user("<command-name>/init</command-name> stuff"),
                user("<command-name>/second</command-name>")));
        assertNotEquals(true, cmd.data().get("first_prompt_locked"));
        assertEquals("/init", cmd.data().get("command_fallback"));
        assertEquals("now real", fold(cmd, List.of(user("now real"))).data().get("first_prompt"));

        String truncated = (String) fold(null, List.of(user("x".repeat(300)))).data().get("first_prompt");
        assertTrue(truncated.length() <= 201 && truncated.endsWith("…"));
    }

    @Test
    void toSessionInfoPrecedenceChain() {
        Map<String, Object> data = new LinkedHashMap<>(Json.obj("first_prompt", "fp", "first_prompt_locked", true,
                "command_fallback", "/cmd", "summary_hint", "sh", "last_prompt", "lp", "ai_title", "ai",
                "custom_title", "ct"));
        SessionSummaryEntry entry = new SessionSummaryEntry("s", 1, data);
        assertEquals("ct", SessionSummaries.toSessionInfo(entry, null).summary());
        data.remove("custom_title");
        assertEquals("ai", SessionSummaries.toSessionInfo(entry, null).customTitle());
        data.remove("ai_title");
        SessionInfo lp = SessionSummaries.toSessionInfo(entry, null);
        assertEquals("lp", lp.summary());
        assertNull(lp.customTitle());
        data.remove("last_prompt");
        assertEquals("sh", SessionSummaries.toSessionInfo(entry, null).summary());
        data.remove("summary_hint");
        assertEquals("fp", SessionSummaries.toSessionInfo(entry, null).firstPrompt());
        data.put("first_prompt_locked", false);
        assertEquals("/cmd", SessionSummaries.toSessionInfo(entry, null).summary());
    }

    @Test
    void toSessionInfoDropsSidechainAndEmptyAndPassesFieldsThrough() {
        assertNull(SessionSummaries.toSessionInfo(new SessionSummaryEntry("s", 1,
                Map.of("is_sidechain", true, "custom_title", "t")), null));
        assertNull(SessionSummaries.toSessionInfo(new SessionSummaryEntry("s", 1, Map.of()), null));
        assertEquals("/proj", SessionSummaries.toSessionInfo(new SessionSummaryEntry("s", 1,
                Map.of("custom_title", "t")), "/proj").cwd());
        SessionInfo info = SessionSummaries.toSessionInfo(new SessionSummaryEntry("s", 99, Map.of(
                "custom_title", "t", "git_branch", "main", "tag", "wip", "created_at", 50, "cwd", "/own")), "/proj");
        assertEquals(new SessionInfo("s", "t", 99, null, "t", null, "main", "/own", "wip", 50L), info);
    }
}
