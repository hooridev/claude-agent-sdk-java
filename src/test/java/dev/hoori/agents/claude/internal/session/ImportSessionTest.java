package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.MutationTestFixture.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionMutations;

class ImportSessionTest {

    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";

    @TempDir
    Path tmp;
    private MutationTestFixture fx;
    private final MutationFakeStore store = new MutationFakeStore();
    private SessionKey mainKey;

    @BeforeEach
    void setUp() {
        fx = new MutationTestFixture(tmp);
        mainKey = SessionKey.of(fx.sessionsDir.getFileName().toString(), SID);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private static List<Map<String, Object>> entries(int count) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(entry(i % 2 == 0 ? "user" : "assistant", "u" + i, i == 0 ? null : "u" + (i - 1)));
        }
        return out;
    }

    @Test
    void importsMainTranscriptInOrder() throws Exception {
        List<Map<String, Object>> source = entries(3);
        fx.writeSession(SID, source);
        SessionMutations.importSessionToStore(SID, store, fx.directory());
        assertEquals(1, store.calls.size());
        assertEquals(source, store.entries(mainKey));
    }

    @Test
    void batchesPerChunkAndNonPositiveSizeUsesDefault() throws Exception {
        fx.writeSession(SID, entries(5));
        SessionMutations.importSessionToStore(SID, store, fx.directory(), true, 2);
        assertEquals(List.of(2, 2, 1), store.calls.stream().map(c -> c.entries().size()).toList());

        MutationFakeStore other = new MutationFakeStore();
        SessionMutations.importSessionToStore(SID, other, fx.directory(), true, 0);
        assertEquals(1, other.calls.size());
    }

    @Test
    void skipsBlankLines() throws Exception {
        MutationTestFixture.writeRaw(fx.sessionsDir.resolve(SID + ".jsonl"),
                Json.write(entry("user", "a", null)) + "\n\n" + Json.write(entry("user", "b", "a")) + "\r\n\n");
        SessionMutations.importSessionToStore(SID, store);
        assertEquals(List.of("a", "b"), store.entries(mainKey).stream().map(e -> e.get("uuid")).toList());
    }

    @Test
    void importsNestedSubagentsWithSubpathAndSidecar() throws Exception {
        fx.writeSession(SID, entries(1));
        Path sessionDir = fx.sessionsDir.resolve(SID);
        Path agent = MutationTestFixture.writeRaw(sessionDir.resolve("subagents/agent-abc.jsonl"),
                Json.write(entry("user", "s1", null)) + "\n");
        MutationTestFixture.writeRaw(sessionDir.resolve("subagents/agent-abc.meta.json"),
                "{\"agentType\":\"general\",\"type\":\"stray\"}");
        Path nested = MutationTestFixture.writeRaw(sessionDir.resolve("subagents/workflows/run-1/agent-def.jsonl"),
                Json.write(entry("user", "s2", null)) + "\n");
        MutationTestFixture.writeRaw(sessionDir.resolve("subagents/agent-bad.jsonl"),
                Json.write(entry("user", "s3", null)) + "\n");
        MutationTestFixture.writeRaw(sessionDir.resolve("subagents/agent-bad.meta.json"), "not json");

        SessionMutations.importSessionToStore(SID, store, fx.directory());

        SessionKey abc = new SessionKey(mainKey.projectKey(), SID, "subagents/agent-abc");
        assertEquals(List.of(entry("user", "s1", null), Map.of("agentType", "general", "type", "agent_metadata")),
                store.entries(abc));
        SessionKey def = new SessionKey(mainKey.projectKey(), SID, "subagents/workflows/run-1/agent-def");
        assertEquals(1, store.entries(def).size());
        assertEquals(1, store.entries(new SessionKey(mainKey.projectKey(), SID, "subagents/agent-bad")).size(),
                "a corrupt sidecar is treated as absent");

        // Keys match what live mirroring derives for the same files.
        String projects = fx.configDir.resolve("projects").toString();
        assertEquals(abc, SessionPaths.filePathToSessionKey(agent.toString(), projects));
        assertEquals(def, SessionPaths.filePathToSessionKey(nested.toString(), projects));
    }

    @Test
    void includeSubagentsFalseSkipsThem() throws Exception {
        fx.writeSession(SID, entries(1));
        MutationTestFixture.writeRaw(fx.sessionsDir.resolve(SID + "/subagents/agent-a.jsonl"),
                Json.write(entry("user", "s", null)) + "\n");
        SessionMutations.importSessionToStore(SID, store, fx.directory(), false, 500);
        assertEquals(List.of(mainKey), store.calls.stream().map(MutationFakeStore.Call::key).toList());
    }

    @Test
    void keysByResolvedProjectDirNotCwd() throws Exception {
        Path elsewhere = MutationTestFixture.writeRaw(fx.configDir.resolve("projects/-other-project/" + SID + ".jsonl"),
                Json.write(entry("user", "x", null)) + "\n");
        SessionMutations.importSessionToStore(SID, store);
        assertTrue(store.data.containsKey(SessionKey.of("-other-project", SID)), elsewhere.toString());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.importSessionToStore("x", store));
        assertThrows(NoSuchFileException.class, () -> SessionMutations.importSessionToStore(SID, store, fx.directory()));
    }
}
