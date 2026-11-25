package dev.hoori.agents.claude.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionInfo;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionSummaries;
import dev.hoori.agents.claude.session.SessionSummaryEntry;

/** Lite head/tail parsing, the shared text helpers, and parity with the incremental summary fold. */
class LiteSessionInfoParserTest {

    private static final String SID = "22222222-2222-4222-8222-222222222222";

    private static SessionInfo parse(String jsonl, String projectPath) {
        return LiteSessionInfoParser.parse(SID, StoreSessionReader.jsonlToLite(jsonl, 1000), projectPath);
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @Test
    void extractsStringFields() {
        assertEquals("bar", SessionText.extractJsonStringField("{\"foo\":\"bar\",\"x\":1}", "foo"));
        assertEquals("bar", SessionText.extractJsonStringField("{\"foo\": \"bar\"}", "foo"));
        assertEquals("a\"b\nc", SessionText.extractJsonStringField("{\"foo\":\"a\\\"b\\nc\"}", "foo"));
        assertNull(SessionText.extractJsonStringField("{\"foo\":\"unterminated", "foo"));
        assertEquals("second", SessionText.extractLastJsonStringField(
                "{\"foo\":\"first\"}\n{\"foo\":\"second\"}", "foo"));
    }

    @Test
    void firstPromptSkipsMetaToolResultsAndCommands() {
        String head = lines(
                "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"content\":\"meta\"}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"content\":\"r\"}]}}",
                "{\"type\":\"user\",\"message\":{\"content\":\"<command-name>/init</command-name>\"}}",
                "{\"type\":\"user\",\"message\":{\"content\":\"<local-command-stdout>out\"}}",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"real\\nprompt\"}]}}");
        assertEquals("real prompt", SessionText.extractFirstPromptFromHead(head));
        assertEquals("/init", SessionText.extractFirstPromptFromHead(
                "{\"type\":\"user\",\"message\":{\"content\":\"<command-name>/init</command-name>\"}}"));
        assertEquals("", SessionText.extractFirstPromptFromHead("{\"type\":\"assistant\"}"));
        String truncated = SessionText.extractFirstPromptFromHead(
                "{\"type\":\"user\",\"message\":{\"content\":\"" + "x".repeat(300) + "\"}}");
        assertEquals(201, truncated.length());
        assertEquals('…', truncated.charAt(200));
    }

    @Test
    void titlePrecedenceAndFallbacks() {
        String base = "{\"type\":\"user\",\"message\":{\"content\":\"hello\"},\"cwd\":\"/own\",\"gitBranch\":\"main\"}";
        SessionInfo info = parse(lines(base), "/proj");
        assertEquals("hello", info.summary());
        assertEquals("hello", info.firstPrompt());
        assertNull(info.customTitle());
        assertEquals("/own", info.cwd());
        assertEquals("main", info.gitBranch());
        assertEquals(1000, info.lastModified());

        assertEquals("sum", parse(lines(base, "{\"type\":\"summary\",\"summary\":\"sum\"}"), null).summary());
        assertEquals("lp", parse(lines(base, "{\"type\":\"summary\",\"summary\":\"sum\"}",
                "{\"type\":\"last-prompt\",\"lastPrompt\":\"lp\"}"), null).summary());
        SessionInfo titled = parse(lines(base, "{\"type\":\"ai-title\",\"aiTitle\":\"ai\"}",
                "{\"type\":\"custom-title\",\"customTitle\":\"mine\",\"gitBranch\":\"feature\"}"), null);
        assertEquals("mine", titled.summary());
        assertEquals("mine", titled.customTitle());
        assertEquals("feature", titled.gitBranch());
        assertEquals("ai", parse(lines(base, "{\"type\":\"ai-title\",\"aiTitle\":\"ai\"}"), null).customTitle());

        assertEquals("/proj", parse(lines("{\"type\":\"user\",\"message\":{\"content\":\"x\"}}"), "/proj").cwd());
    }

    @Test
    void sidechainAndMetadataOnlySessionsAreDropped() {
        assertNull(parse(lines("{\"type\":\"user\",\"isSidechain\":true,\"message\":{\"content\":\"x\"}}"), null));
        assertNull(parse(lines("{\"type\":\"permission-mode\",\"mode\":\"default\"}"), null));
    }

    @Test
    void tagIsScopedToTagLinesAndLastWins() {
        String user = "{\"type\":\"user\",\"message\":{\"content\":\"tag this\"}}";
        String dockerTag = "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\","
                + "\"input\":{\"tag\":\"myapp:v2\"}}]}}";
        assertEquals("real-tag", parse(lines(user, "{\"type\":\"tag\",\"tag\":\"real-tag\"}", dockerTag), null).tag());
        assertNull(parse(lines(user, dockerTag), null).tag());
        assertEquals("second", parse(lines(user, "{\"type\":\"tag\",\"tag\":\"first\"}",
                "{\"type\":\"tag\",\"tag\":\"second\"}"), null).tag());
        assertNull(parse(lines(user, "{\"type\":\"tag\",\"tag\":\"old\"}", "{\"type\":\"tag\",\"tag\":\"\"}"),
                null).tag());
    }

    @Test
    void createdAtFromFirstTimestampInHead() {
        String jsonl = lines("{\"type\":\"permission-mode\",\"mode\":\"default\"}",
                "{\"type\":\"user\",\"timestamp\":\"2026-01-15T10:30:00.000Z\",\"message\":{\"content\":\"hi\"}}",
                "{\"type\":\"assistant\",\"timestamp\":\"2026-01-15T10:31:00.000Z\"}");
        assertEquals(Instant.parse("2026-01-15T10:30:00Z").toEpochMilli(), parse(jsonl, null).createdAt());
        assertEquals(Instant.parse("2026-01-15T10:30:00Z").toEpochMilli(), parse(lines(
                "{\"type\":\"user\",\"timestamp\":\"2026-01-15T10:30:00+00:00\",\"message\":{\"content\":\"hi\"}}"),
                null).createdAt());
        assertNull(parse(lines("{\"type\":\"user\",\"timestamp\":\"garbage\",\"message\":{\"content\":\"hi\"}}"),
                null).createdAt());
        assertNull(parse(lines("{\"type\":\"user\",\"message\":{\"content\":\"hi\"}}"), null).createdAt());
    }

    @Test
    void storeJsonlHoistsTypeAndSlicesHeadAndTail() {
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("tag", "wip");
        reordered.put("type", "tag");
        String jsonl = StoreSessionReader.toJsonl(List.of(
                Json.obj("message", Json.obj("content", "hi"), "type", "user"), reordered));
        assertEquals("wip", parse(jsonl, null).tag());

        String big = "{\"type\":\"user\",\"message\":{\"content\":\"first\"}}\n" + "x".repeat(200_000) + "\n"
                + "{\"type\":\"custom-title\",\"customTitle\":\"end\"}\n";
        SessionText.LiteSessionFile lite = StoreSessionReader.jsonlToLite(big, 5);
        assertEquals(SessionText.LITE_READ_BUF_SIZE, lite.head().length());
        assertEquals(SessionText.LITE_READ_BUF_SIZE, lite.tail().length());
        assertEquals(big.length(), lite.size());
        assertEquals("end", LiteSessionInfoParser.parse(SID, lite, null).customTitle());

        assertEquals(Instant.parse("2024-01-01T00:00:05Z").toEpochMilli(), StoreSessionReader.mtimeFromJsonlTail(
                "{\"type\":\"user\"}\n{\"type\":\"x\",\"timestamp\":\"2024-01-01T00:00:05Z\"}\n"));
    }

    // ---------------------------------------------------------------- parity with the incremental fold

    private static Map<String, Object> user(Object content, String ts, Object... extras) {
        Map<String, Object> entry = Json.obj("type", "user", "timestamp", ts, "message",
                Json.obj("role", "user", "content", content));
        entry.putAll(Json.obj(extras));
        return entry;
    }

    private static void assertParity(List<Map<String, Object>> entries, int split, String projectPath) {
        SessionKey key = SessionKey.of("proj", SID);
        SessionSummaryEntry folded = SessionSummaries.fold(null, key, entries.subList(0, split));
        folded = SessionSummaries.fold(folded, key, entries.subList(split, entries.size()));
        SessionInfo incremental = SessionSummaries.toSessionInfo(folded, projectPath);
        SessionInfo batch = LiteSessionInfoParser.parse(SID,
                StoreSessionReader.jsonlToLite(StoreSessionReader.toJsonl(entries), folded.mtime()), projectPath);
        assertNotNull(incremental);
        assertNotNull(batch);
        // fileSize is only meaningful for the JSONL path
        assertEquals(incremental, new SessionInfo(batch.sessionId(), batch.summary(), batch.lastModified(), null,
                batch.customTitle(), batch.firstPrompt(), batch.gitBranch(), batch.cwd(), batch.tag(),
                batch.createdAt()));
    }

    @Test
    void incrementalFoldEqualsBatchLiteParse() {
        List<Map<String, Object>> entries = new ArrayList<>(List.of(
                user("<command-name>/clear</command-name>", "2024-01-01T00:00:00.000Z", "cwd", "/work",
                        "gitBranch", "main"),
                user("ignored", "2024-01-01T00:00:01.000Z", "isMeta", true),
                user("real prompt here", "2024-01-01T00:00:02.000Z"),
                Json.obj("type", "assistant", "timestamp", "2024-01-01T00:00:03.000Z",
                        "message", Json.obj("content", List.of(Json.obj("type", "text", "text", "ok")))),
                Json.obj("type", "x", "timestamp", "2024-01-01T00:00:04.000Z", "aiTitle", "AI Named"),
                Json.obj("type", "tag", "timestamp", "2024-01-01T00:00:05.000Z", "tag", "wip"),
                Json.obj("type", "x", "timestamp", "2024-01-01T00:00:06.000Z", "customTitle", "User Named",
                        "gitBranch", "feature")));
        assertParity(entries, 3, "/work");
        assertParity(List.of(user("just a prompt", "2024-02-01T00:00:00.000Z", "cwd", "/w")), 1, "/w");
    }
}
