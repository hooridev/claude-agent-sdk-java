package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.MutationTestFixture.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.internal.Json;

class ForkTranscriptBuilderTest {

    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String U1 = "11111111-1111-4111-8111-111111111111";
    private static final String P1 = "22222222-2222-4222-8222-222222222222";
    private static final String A1 = "33333333-3333-4333-8333-333333333333";
    private static final String U2 = "44444444-4444-4444-8444-444444444444";

    private static List<Map<String, Object>> transcript() {
        return List.of(
                entry("user", U1, null, "sessionId", SID, "timestamp", "2024-01-01T00:00:00Z",
                        "message", Map.of("role", "user", "content", "hello world")),
                entry("progress", P1, U1, "sessionId", SID),
                entry("assistant", A1, P1, "sessionId", SID, "timestamp", "2024-01-01T00:00:01Z",
                        "teamName", "t", "agentName", "a", "slug", "s", "sourceToolAssistantUUID", "x",
                        "isSidechain", false),
                entry("user", "sc-1", A1, "isSidechain", true),
                entry("user", U2, A1, "sessionId", SID, "logicalParentUuid", U1, "timestamp", "2024-01-01T00:00:02Z"));
    }

    private static ForkTranscriptBuilder.Fork fork(List<Map<String, Object>> entries, String upTo, String title) {
        return ForkTranscriptBuilder.build(ForkTranscriptBuilder.partition(entries, SID), SID, upTo, title,
                () -> ForkTranscriptBuilder.deriveTitleFromEntries(entries));
    }

    @Test
    void remapsUuidsSkippingProgressAndSidechains() {
        ForkTranscriptBuilder.Fork fork = fork(transcript(), null, null);
        List<Map<String, Object>> out = fork.entries();
        assertEquals(4, out.size()); // user, assistant, user, custom-title
        Map<String, Object> user = out.get(0);
        Map<String, Object> assistant = out.get(1);
        Map<String, Object> user2 = out.get(2);

        Set<Object> newUuids = new HashSet<>();
        out.forEach(e -> newUuids.add(e.get("uuid")));
        assertEquals(4, newUuids.size());
        assertFalse(newUuids.contains(U1) || newUuids.contains(A1) || newUuids.contains(U2));

        assertNull(user.get("parentUuid"));
        assertEquals(user.get("uuid"), assistant.get("parentUuid"), "progress ancestor is skipped");
        assertEquals(assistant.get("uuid"), user2.get("parentUuid"));
        assertEquals(user.get("uuid"), user2.get("logicalParentUuid"));
        assertTrue(user.containsKey("logicalParentUuid") && user.get("logicalParentUuid") == null);

        for (Map<String, Object> e : out.subList(0, 3)) {
            assertEquals(fork.sessionId(), e.get("sessionId"));
            assertEquals(false, e.get("isSidechain"));
        }
        assertEquals(Map.of("sessionId", SID, "messageUuid", A1), assistant.get("forkedFrom"));
        for (String leaked : List.of("teamName", "agentName", "slug", "sourceToolAssistantUUID")) {
            assertFalse(assistant.containsKey(leaked), leaked);
        }
        assertEquals("2024-01-01T00:00:00Z", user.get("timestamp"));
        assertNotEquals("2024-01-01T00:00:02Z", user2.get("timestamp"), "the leaf gets a fresh timestamp");
        assertEquals("hello world (fork)", out.getLast().get("customTitle"));
    }

    @Test
    void sourceEntriesAreNotMutated() {
        List<Map<String, Object>> source = transcript();
        String before = Json.write(source);
        fork(source, null, null);
        assertEquals(before, Json.write(source));
    }

    @Test
    void upToMessageIdIsInclusive() {
        ForkTranscriptBuilder.Fork fork = fork(transcript(), A1, "Mine");
        assertEquals(3, fork.entries().size());
        assertEquals(A1, Json.object(fork.entries().get(1), "forkedFrom").get("messageUuid"));
        assertEquals("Mine", fork.entries().getLast().get("customTitle"));
    }

    @Test
    void unknownUpToMessageIdOrEmptyTranscriptFails() {
        assertThrows(IllegalArgumentException.class,
                () -> fork(transcript(), "99999999-9999-4999-8999-999999999999", null));
        assertThrows(IllegalArgumentException.class, () -> fork(List.of(entry("user", "s", null,
                "isSidechain", true)), null, null));
        assertThrows(IllegalArgumentException.class, () -> fork(List.of(entry("progress", "p", null)), null, null));
    }

    @Test
    void reEmitsContentReplacementsOfTheSourceSession() {
        List<Map<String, Object>> entries = new java.util.ArrayList<>(transcript());
        entries.add(Json.obj("type", "content-replacement", "sessionId", SID, "replacements", List.of(Map.of("k", 1))));
        entries.add(Json.obj("type", "content-replacement", "sessionId", "other", "replacements", List.of(Map.of("k", 2))));
        ForkTranscriptBuilder.Fork fork = fork(entries, null, "t");
        Map<String, Object> replacement = fork.entries().get(fork.entries().size() - 2);
        assertEquals("content-replacement", replacement.get("type"));
        assertEquals(fork.sessionId(), replacement.get("sessionId"));
        assertEquals(List.of(Map.of("k", 1)), replacement.get("replacements"));
    }

    @Test
    void parsesJsonlSkippingGarbageAndUntypedLines() {
        String jsonl = Json.write(entry("user", U1, null)) + "\n\nnot json\n[1]\n"
                + Json.write(Map.of("type", "summary", "uuid", "s")) + "\n" + Json.write(Map.of("type", "user"));
        ForkTranscriptBuilder.Parsed parsed = ForkTranscriptBuilder.parseJsonl(jsonl.getBytes(StandardCharsets.UTF_8), SID);
        assertEquals(1, parsed.transcript().size());
    }

    @Test
    void titleDerivationPrecedence() {
        String custom = Json.write(Map.of("type", "custom-title", "customTitle", "Custom")) + "\n";
        String ai = Json.write(Map.of("type", "ai-title", "aiTitle", "Ai")) + "\n";
        String user = Json.write(entry("user", U1, null, "message", Map.of("role", "user", "content", "prompt"))) + "\n";
        assertEquals("Custom", ForkTranscriptBuilder.deriveTitleFromBytes((user + custom + ai).getBytes(StandardCharsets.UTF_8)));
        assertEquals("Ai", ForkTranscriptBuilder.deriveTitleFromBytes((user + ai).getBytes(StandardCharsets.UTF_8)));
        assertEquals("prompt", ForkTranscriptBuilder.deriveTitleFromBytes(user.getBytes(StandardCharsets.UTF_8)));
        assertNull(ForkTranscriptBuilder.deriveTitleFromEntries(List.of(entry("assistant", A1, null))));
        assertEquals("Forked session (fork)",
                fork(List.of(entry("assistant", A1, null)), null, null).entries().getLast().get("customTitle"));
    }
}
