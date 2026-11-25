package dev.hoori.agents.claude.session;

import static dev.hoori.agents.claude.internal.session.MutationTestFixture.entry;
import static dev.hoori.agents.claude.internal.session.MutationTestFixture.readEntries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hoori.agents.claude.internal.session.MutationFakeStore;
import dev.hoori.agents.claude.internal.session.MutationTestFixture;
import dev.hoori.agents.claude.internal.session.SessionPaths;

class SessionMutationsTest {

    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";

    @TempDir
    Path tmp;
    private MutationTestFixture fx;
    private Path file;

    @BeforeEach
    void setUp() {
        fx = new MutationTestFixture(tmp);
        file = fx.writeSession(SID, List.of(entry("user", "u1", null)));
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Map<String, Object> lastEntry() {
        List<Map<String, Object>> entries = readEntries(file);
        return entries.getLast();
    }

    // ------------------------------------------------------------------ rename

    @Test
    void renameAppendsCompactTrimmedCustomTitle() throws Exception {
        SessionMutations.renameSession(SID, "  My title  ", fx.directory());
        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("{\"type\":\"custom-title\",\"customTitle\":\"My title\",\"sessionId\":\"" + SID
                + "\"}\n"), text);
    }

    @Test
    void renameLastWinsAndSearchesAllProjects() throws Exception {
        SessionMutations.renameSession(SID, "first");
        SessionMutations.renameSession(SID, "second");
        List<Map<String, Object>> entries = readEntries(file);
        assertEquals(3, entries.size());
        assertEquals("second", entries.getLast().get("customTitle"));
    }

    @Test
    void renameValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.renameSession("not-a-uuid", "t"));
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.renameSession(SID, "   "));
    }

    @Test
    void renameMissingSessionThrowsNoSuchFile() {
        String other = "660e8400-e29b-41d4-a716-446655440000";
        assertThrows(NoSuchFileException.class, () -> SessionMutations.renameSession(other, "t", fx.directory()));
        assertThrows(NoSuchFileException.class, () -> SessionMutations.renameSession(other, "t"));
    }

    @Test
    void renameSkipsZeroByteStubInOtherProject() throws Exception {
        Path stub = MutationTestFixture.writeRaw(fx.configDir.resolve("projects/-aaa-stub/" + SID + ".jsonl"), "");
        SessionMutations.renameSession(SID, "t");
        assertEquals(0, Files.size(stub));
        assertEquals("t", lastEntry().get("customTitle"));
    }

    @Test
    void renameWithoutProjectsDirThrowsNoSuchFile() {
        System.setProperty(SessionPaths.CONFIG_DIR_PROPERTY, tmp.resolve("nowhere").toString());
        assertThrows(NoSuchFileException.class, () -> SessionMutations.renameSession(SID, "t"));
    }

    // ------------------------------------------------------------------ tag

    @Test
    void tagIsSanitizedAndTrimmed() throws Exception {
        SessionMutations.tagSession(SID, " exp​eriment﻿ ", fx.directory());
        Map<String, Object> last = lastEntry();
        assertEquals("tag", last.get("type"));
        assertEquals("experiment", last.get("tag"));
        assertEquals(SID, last.get("sessionId"));
    }

    @Test
    void tagNullClearsWithEmptyString() throws Exception {
        SessionMutations.tagSession(SID, "a");
        SessionMutations.tagSession(SID, null);
        assertEquals("", lastEntry().get("tag"));
    }

    @Test
    void tagRejectsPureInvisibleOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.tagSession(SID, "​‍﻿"));
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.tagSession(SID, "  "));
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteRemovesFileAndSubagentDir() throws Exception {
        Path sub = MutationTestFixture.writeRaw(fx.sessionsDir.resolve(SID + "/subagents/agent-a.jsonl"), "{}\n");
        SessionMutations.deleteSession(SID, fx.directory());
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(sub.getParent().getParent()));
        assertThrows(NoSuchFileException.class, () -> SessionMutations.deleteSession(SID));
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.deleteSession("x"));
    }

    // ------------------------------------------------------------------ fork (disk; transform details in ForkTranscriptBuilderTest)

    @Test
    void forkWritesNewFileNextToSource() throws Exception {
        ForkSessionResult result = SessionMutations.forkSession(SID, fx.directory());
        assertNotEquals(SID, result.sessionId());
        Path forked = fx.sessionsDir.resolve(result.sessionId() + ".jsonl");
        List<Map<String, Object>> entries = readEntries(forked);
        assertEquals(2, entries.size());
        assertEquals(result.sessionId(), entries.getFirst().get("sessionId"));
        assertEquals("custom-title", entries.getLast().get("type"));
        if (!System.getProperty("os.name").startsWith("Windows")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                    Files.getPosixFilePermissions(forked)));
        }
    }

    @Test
    void forkValidatesIds() {
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.forkSession("bad"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionMutations.forkSession(SID, null, "not-a-uuid", null));
        assertThrows(NoSuchFileException.class,
                () -> SessionMutations.forkSession("660e8400-e29b-41d4-a716-446655440000"));
    }

    // ------------------------------------------------------------------ store variants

    @Test
    void renameAndTagViaStoreAppendStampedEntries() throws Exception {
        MutationFakeStore store = new MutationFakeStore();
        SessionMutations.renameSessionViaStore(store, SID, " T ", fx.directory());
        SessionMutations.tagSessionViaStore(store, SID, null, fx.directory());
        SessionKey key = SessionKey.of(SessionPaths.projectKeyForDirectory(fx.directory()), SID);
        List<Map<String, Object>> entries = store.entries(key);
        assertEquals(2, entries.size());
        assertEquals("T", entries.get(0).get("customTitle"));
        assertNotNull(entries.get(0).get("uuid"));
        assertNotNull(entries.get(0).get("timestamp"));
        assertEquals("", entries.get(1).get("tag"));
        assertThrows(IllegalArgumentException.class, () -> SessionMutations.tagSessionViaStore(store, SID, "​"));
    }

    @Test
    void deleteViaStoreIsNoOpWithoutDelete() throws Exception {
        SessionStore minimal = new SessionStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) {
            }

            @Override
            public List<Map<String, Object>> load(SessionKey key) {
                return null;
            }
        };
        SessionMutations.deleteSessionViaStore(minimal, SID); // must not throw UnsupportedOperationException

        MutationFakeStore store = new MutationFakeStore();
        SessionMutations.deleteSessionViaStore(store, SID, fx.directory());
        assertEquals(List.of(SessionKey.of(SessionPaths.projectKeyForDirectory(fx.directory()), SID)), store.deleted);
    }

    @Test
    void forkViaStoreAppendsUnderNewKey() throws Exception {
        MutationFakeStore store = new MutationFakeStore();
        String projectKey = SessionPaths.projectKeyForDirectory(fx.directory());
        store.append(SessionKey.of(projectKey, SID), List.of(
                entry("user", "u1", null, "message", Map.of("role", "user", "content", "hello there")),
                entry("assistant", "a1", "u1"),
                Map.of("type", "custom-title", "customTitle", "Orig", "sessionId", SID)));
        ForkSessionResult result = SessionMutations.forkSessionViaStore(store, SID, fx.directory(), null, null);
        List<Map<String, Object>> forked = store.entries(SessionKey.of(projectKey, result.sessionId()));
        assertEquals(3, forked.size());
        assertEquals(forked.get(0).get("uuid"), forked.get(1).get("parentUuid"));
        assertEquals("Orig (fork)", forked.get(2).get("customTitle"));
        assertThrows(NoSuchFileException.class, () -> SessionMutations.forkSessionViaStore(store,
                "660e8400-e29b-41d4-a716-446655440000", fx.directory(), null, null));
    }
}
