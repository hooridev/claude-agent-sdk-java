package dev.hoori.agents.claude.internal.session;

import static dev.hoori.agents.claude.internal.session.MutationTestFixture.entry;
import static dev.hoori.agents.claude.internal.session.MutationTestFixture.readEntries;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionStore;

class ResumeSessionTest {

    private static final String SID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String SID2 = "660e8400-e29b-41d4-a716-446655440000";

    @TempDir
    Path tmp;
    private Path cwd;
    private Path home;
    private String projectKey;
    private final MutationFakeStore store = new MutationFakeStore();
    private final List<Path> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        cwd = Files.createDirectories(tmp.resolve("project"));
        home = Files.createDirectories(tmp.resolve("home"));
        projectKey = SessionPaths.projectKeyForDirectory(cwd.toString());
    }

    /** A seeder with an empty process env, the fake home and the given keychain content. */
    private ResumeConfigSeeder seeder(String keychain) {
        return new ResumeConfigSeeder(Map.of(), home, () -> keychain);
    }

    private ClaudeAgentOptions.Builder options() {
        return ClaudeAgentOptions.builder().cwd(cwd).sessionStore(store);
    }

    private MaterializedResume materialize(ClaudeAgentOptions options) {
        return materialize(options, seeder(null));
    }

    private MaterializedResume materialize(ClaudeAgentOptions options, ResumeConfigSeeder seeder) {
        MaterializedResume m = SessionResume.materialize(options, seeder);
        if (m != null) {
            created.add(m.configDir());
        }
        return m;
    }

    private void seedMain(String sessionId, Map<String, Object>... entries) throws Exception {
        store.append(SessionKey.of(projectKey, sessionId), List.of(entries));
    }

    private static Map<String, Object> read(Path file) throws Exception {
        return Json.parseObject(Files.readString(file));
    }

    // ------------------------------------------------------------------ no materialization

    @Test
    void returnsNullWhenNothingToMaterialize() throws Exception {
        assertNull(materialize(ClaudeAgentOptions.builder().cwd(cwd).resume(SID).build()));
        assertNull(materialize(options().build()));
        assertNull(materialize(options().resume("../../etc/passwd").build()));
        assertNull(materialize(options().resume(SID).build()), "unknown session");
        store.append(SessionKey.of(projectKey, SID), List.of());
        assertNull(materialize(options().resume(SID).build()), "empty session");
        assertNull(materialize(ClaudeAgentOptions.builder().cwd(cwd).sessionStore(new MutationFakeStore())
                .continueConversation(true).build()));
    }

    // ------------------------------------------------------------------ happy paths

    @Test
    void writesJsonlAndCleanupRemovesDir() throws Exception {
        Map<String, Object> user = entry("user", "u1", null, "message", Map.of("role", "user", "content", "hi \"q\"\n"));
        Map<String, Object> assistant = entry("assistant", "a1", "u1", "nested", Json.obj("b", List.of(1, 2)));
        seedMain(SID, user, assistant);
        MaterializedResume m = materialize(options().resume(SID).build());
        assertNotNull(m);
        assertEquals(SID, m.resumeSessionId());
        Path jsonl = m.configDir().resolve("projects").resolve(projectKey).resolve(SID + ".jsonl");
        assertEquals(Json.write(user) + "\n" + Json.write(assistant) + "\n", Files.readString(jsonl));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(jsonl)));
        m.cleanup();
        assertFalse(Files.exists(m.configDir()));
    }

    @Test
    void applyPointsOptionsAtMaterializedDir() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        ClaudeAgentOptions original = options().continueConversation(true).env("KEEP", "1").build();
        MaterializedResume m = materialize(original);
        ClaudeAgentOptions applied = SessionResume.apply(original, m);
        assertEquals(m.configDir().toString(), applied.env().get("CLAUDE_CONFIG_DIR"));
        assertEquals("1", applied.env().get("KEEP"));
        assertEquals(SID, applied.resume());
        assertFalse(applied.continueConversation());
        assertNull(original.env().get("CLAUDE_CONFIG_DIR"), "the original options are unchanged");
    }

    @Test
    void credentialsAreCopiedWithRefreshTokenRedacted() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        Path config = Files.createDirectories(home.resolve(".claude"));
        Files.writeString(config.resolve(".credentials.json"),
                "{\"claudeAiOauth\":{\"accessToken\":\"at\",\"refreshToken\":\"rt\",\"expiresAt\":1},\"other\":1}");
        Files.writeString(home.resolve(".claude.json"), "{\"userID\":\"x\"}");
        MaterializedResume m = materialize(options().resume(SID).build());
        Map<String, Object> creds = read(m.configDir().resolve(".credentials.json"));
        assertEquals(Map.of("accessToken", "at", "expiresAt", 1), creds.get("claudeAiOauth"));
        assertEquals(1, creds.get("other"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(m.configDir().resolve(".credentials.json"))));
        assertEquals("{\"userID\":\"x\"}", Files.readString(m.configDir().resolve(".claude.json")));
    }

    @Test
    void callerConfigDirFromOptionsEnvWins() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        Path caller = Files.createDirectories(tmp.resolve("caller-config"));
        Files.writeString(caller.resolve(".credentials.json"), "{\"claudeAiOauth\":{\"accessToken\":\"c\"}}");
        Files.writeString(caller.resolve(".claude.json"), "{\"from\":\"caller\"}");
        Files.writeString(caller.resolve("settings.json"), "{\"apiKeyHelper\":\"h\"}");
        MaterializedResume m = materialize(options().resume(SID).env("CLAUDE_CONFIG_DIR", caller.toString()).build(),
                seeder("{\"claudeAiOauth\":{\"accessToken\":\"keychain\"}}"));
        assertEquals("c", Json.object(read(m.configDir().resolve(".credentials.json")), "claudeAiOauth")
                .get("accessToken"), "keychain is not consulted with a custom config dir");
        assertEquals("caller", read(m.configDir().resolve(".claude.json")).get("from"));
        assertEquals("{\"apiKeyHelper\":\"h\"}", Files.readString(m.configDir().resolve("settings.json")));
    }

    @Test
    void keychainFallbackOnlyWithoutEnvAuth() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        String keychain = "{\"claudeAiOauth\":{\"accessToken\":\"k\",\"refreshToken\":\"r\"}}";
        MaterializedResume m = materialize(options().resume(SID).build(), seeder(keychain));
        assertEquals(Map.of("claudeAiOauth", Map.of("accessToken", "k")), read(m.configDir().resolve(".credentials.json")));

        MaterializedResume withKey = materialize(options().resume(SID).env("ANTHROPIC_API_KEY", "sk").build(),
                seeder(keychain));
        assertFalse(Files.exists(withKey.configDir().resolve(".credentials.json")));
        MaterializedResume withProcessToken = materialize(options().resume(SID).build(),
                new ResumeConfigSeeder(Map.of("CLAUDE_CODE_OAUTH_TOKEN", "t"), home, () -> keychain));
        assertFalse(Files.exists(withProcessToken.configDir().resolve(".credentials.json")));
    }

    @Test
    void settingsStripPluginsAndConfigDirEnv() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        Path config = Files.createDirectories(home.resolve(".claude"));
        Files.writeString(config.resolve("settings.json"), "\uFEFF{\"enabledPlugins\":{\"a\":true},"
                + "\"extraKnownMarketplaces\":{},\"env\":{\"CLAUDE_CONFIG_DIR\":\"/x\",\"FOO\":\"1\"},\"model\":\"m\"}");
        Files.writeString(config.resolve("cowork_settings.json"), "{not json");
        MaterializedResume m = materialize(options().resume(SID).build());
        assertEquals(Map.of("env", Map.of("FOO", "1"), "model", "m"), read(m.configDir().resolve("settings.json")));
        assertEquals("{not json", Files.readString(m.configDir().resolve("cowork_settings.json")));
    }

    @Test
    void settingsStripFallsBackToOriginalBytes() {
        byte[] overflow = "{\"enabledPlugins\":{},\"x\":1e999}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(overflow, ResumeConfigSeeder.stripSettings(overflow));
        byte[] array = "[1,2]".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(array, ResumeConfigSeeder.stripSettings(array));
        byte[] untouched = "{ \"model\" : \"m\" }".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(untouched, ResumeConfigSeeder.stripSettings(untouched), "nothing stripped: bytes kept");
        byte[] invalidUtf8 = {'{', (byte) 0xff, '}'};
        assertArrayEquals(invalidUtf8, ResumeConfigSeeder.stripSettings(invalidUtf8));
    }

    @Test
    void unreadableSeedFilesDoNotAbortResume() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        Path config = Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(config.resolve("settings.json")); // a directory where a file is expected
        MaterializedResume m = materialize(options().resume(SID).build());
        assertNotNull(m);
        assertFalse(Files.exists(m.configDir().resolve("settings.json")));
        assertFalse(Files.exists(m.configDir().resolve(".credentials.json")));
    }

    @Test
    void continuePicksMostRecentNonSidechain() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        seedMain(SID2, entry("user", "s1", null, "isSidechain", true));
        store.mtimes.put(projectKey + "/" + SID, 10L);
        store.mtimes.put(projectKey + "/" + SID2, 20L);
        store.mtimes.put(projectKey + "/not-a-uuid", 30L);
        MaterializedResume m = materialize(options().continueConversation(true).build());
        assertEquals(SID, m.resumeSessionId());

        store.mtimes.put(projectKey + "/" + SID2, 5L);
        store.data.put(SessionKey.of(projectKey, SID2), List.of(entry("user", "s2", null)));
        assertEquals(SID, materialize(options().continueConversation(true).build()).resumeSessionId());
        store.mtimes.put(projectKey + "/" + SID2, 50L);
        assertEquals(SID2, materialize(options().continueConversation(true).build()).resumeSessionId());
    }

    @Test
    void continueReturnsNullWhenOnlySidechains() throws Exception {
        seedMain(SID, entry("user", "s1", null, "isSidechain", true));
        assertNull(materialize(options().continueConversation(true).build()));
    }

    // ------------------------------------------------------------------ subkeys

    @Test
    void subagentTranscriptAndMetadataSidecar() throws Exception {
        seedMain(SID, entry("user", "u1", null));
        store.append(new SessionKey(projectKey, SID, "subagents/agent-abc"), List.of(
                entry("user", "su1", null), entry("assistant", "sa1", "su1"),
                Json.obj("type", "agent_metadata", "agentType", "general", "ver", 1)));
        MaterializedResume m = materialize(options().resume(SID).build());
        Path sessionDir = m.configDir().resolve("projects").resolve(projectKey).resolve(SID);
        assertEquals(List.of(entry("user", "su1", null), entry("assistant", "sa1", "su1")),
                readEntries(sessionDir.resolve("subagents/agent-abc.jsonl")));
        assertEquals(Map.of("agentType", "general", "ver", 1), read(sessionDir.resolve("subagents/agent-abc.meta.json")));
    }

    @Test
    void unsafeSubpathsAreNeverLoadedOrWritten() throws Exception {
        List<String> evil = List.of("", ".", "./", "a/.", "subagents/.", "/etc/passwd", "../escape", "a/../b",
                "C:escape", "C:\\abs", "\\\\server\\share", "subagents/agent\0x", "subagents/agent-ok");
        MutationFakeStore evilStore = new MutationFakeStore() {
            @Override
            public List<String> listSubkeys(dev.hoori.agents.claude.session.SessionListSubkeysKey key) {
                return evil;
            }

            @Override
            public List<Map<String, Object>> load(SessionKey key) {
                if (key.subpath() == null) {
                    return List.of(entry("user", "main", null));
                }
                if (key.subpath().equals("subagents/agent-ok")) {
                    return List.of(entry("user", "ok", null));
                }
                throw new AssertionError("loaded unsafe subpath " + key.subpath());
            }
        };
        MaterializedResume m = materialize(options().sessionStore(evilStore).resume(SID).build());
        Path projectDir = m.configDir().resolve("projects").resolve(projectKey);
        assertTrue(Files.isRegularFile(projectDir.resolve(SID).resolve("subagents/agent-ok.jsonl")));
        assertEquals(List.of(entry("user", "main", null)), readEntries(projectDir.resolve(SID + ".jsonl")));
        try (Stream<Path> files = Files.walk(m.configDir())) {
            assertEquals(2, files.filter(Files::isRegularFile).count(), "only the main and the safe transcript");
        }
        try (Stream<Path> siblings = Files.list(m.configDir().getParent())) {
            assertTrue(siblings.noneMatch(p -> p.getFileName().toString().startsWith("escape")));
        }
    }

    @Test
    void storeWithoutListSubkeysSkipsSubagents() {
        SessionStore minimal = new SessionStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) {
            }

            @Override
            public List<Map<String, Object>> load(SessionKey key) {
                return List.of(entry("user", "u1", null));
            }
        };
        MaterializedResume m = materialize(options().sessionStore(minimal).resume(SID).build());
        Path projectDir = m.configDir().resolve("projects").resolve(projectKey);
        assertTrue(Files.isRegularFile(projectDir.resolve(SID + ".jsonl")));
        assertFalse(Files.exists(projectDir.resolve(SID)));
    }

    // ------------------------------------------------------------------ errors

    @Test
    void loadTimeoutRaises() {
        SessionStore slow = new MutationFakeStore() {
            @Override
            public List<Map<String, Object>> load(SessionKey key) throws Exception {
                Thread.sleep(5_000);
                return null;
            }
        };
        ClaudeSdkException e = assertThrows(ClaudeSdkException.class,
                () -> materialize(options().sessionStore(slow).resume(SID).loadTimeoutMs(50).build()));
        assertTrue(e.getMessage().contains("timed out after 50ms"), e.getMessage());
    }

    @Test
    void loadExceptionIsWrapped() {
        SessionStore failing = new MutationFakeStore() {
            @Override
            public List<Map<String, Object>> load(SessionKey key) {
                throw new IllegalStateException("boom");
            }
        };
        ClaudeSdkException e = assertThrows(ClaudeSdkException.class,
                () -> materialize(options().sessionStore(failing).resume(SID).build()));
        assertTrue(e.getMessage().contains("failed during resume materialization: boom"), e.getMessage());
    }

    @Test
    void failureAfterTempDirCreationRemovesIt() throws Exception {
        AtomicReference<Path> seeded = new AtomicReference<>();
        SessionStore slowSubkeys = new MutationFakeStore() {
            @Override
            public List<String> listSubkeys(dev.hoori.agents.claude.session.SessionListSubkeysKey key)
                    throws Exception {
                Thread.sleep(5_000);
                return List.of();
            }
        };
        ((MutationFakeStore) slowSubkeys).append(SessionKey.of(projectKey, SID), List.of(entry("user", "u1", null)));
        ResumeConfigSeeder recording = new ResumeConfigSeeder(Map.of(), home, () -> null) {
            @Override
            void seed(Path tmpBase, Map<String, String> optionEnv) {
                seeded.set(tmpBase);
            }
        };
        assertThrows(ClaudeSdkException.class, () -> SessionResume.materialize(
                options().sessionStore(slowSubkeys).resume(SID).loadTimeoutMs(50).build(), recording));
        assertNotNull(seeded.get());
        assertFalse(Files.exists(seeded.get()), "temp dir removed on failure");
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        created.forEach(MaterializedResume::deleteWithRetry);
    }
}
