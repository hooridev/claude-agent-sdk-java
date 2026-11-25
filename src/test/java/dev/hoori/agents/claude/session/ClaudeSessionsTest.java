package dev.hoori.agents.claude.session;

import static dev.hoori.agents.claude.internal.session.TranscriptFixtures.entry;
import static dev.hoori.agents.claude.internal.session.TranscriptFixtures.jsonl;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.session.SessionPaths;

/** The local-disk read functions, against a temporary config directory. */
class ClaudeSessionsTest {

    @TempDir
    Path tmp;

    private Path projectsDir;
    private String projectPath;
    private Path projectDir;

    @BeforeEach
    void setUp() throws IOException {
        Path config = tmp.resolve(".claude");
        projectsDir = Files.createDirectories(config.resolve("projects"));
        System.setProperty(SessionPaths.CONFIG_DIR_PROPERTY, config.toString());
        projectPath = Files.createDirectories(tmp.resolve("proj")).toString();
        projectDir = projectDirFor(projectPath);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(SessionPaths.CONFIG_DIR_PROPERTY);
    }

    private Path projectDirFor(String path) throws IOException {
        return Files.createDirectories(projectsDir.resolve(
                SessionPaths.sanitizePath(SessionPaths.canonicalizePath(path))));
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static Path write(Path dir, String sessionId, String content) throws IOException {
        return Files.writeString(dir.resolve(sessionId + ".jsonl"), content);
    }

    /** A user prompt, an assistant reply and a tail metadata line (like the Python fixture). */
    private static String session(String firstPrompt, Object... tailFields) {
        return jsonl(List.of(
                Json.obj("type", "user", "message", Json.obj("role", "user", "content", firstPrompt)),
                Json.obj("type", "assistant", "message", Json.obj("role", "assistant", "content", "Hi there!")),
                Json.obj(tailFields.length == 0 ? new Object[] {"type", "summary"} : tailFields)));
    }

    private static void touch(Path file, long epochMs) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(epochMs));
    }

    private List<SessionInfo> listProject() {
        return ClaudeSessions.listSessions(projectPath, 0, 0, false);
    }

    // ---------------------------------------------------------------- listSessions

    @Test
    void emptyOrMissingConfigDirListsNothing() {
        assertEquals(List.of(), ClaudeSessions.listSessions());
        System.setProperty(SessionPaths.CONFIG_DIR_PROPERTY, tmp.resolve("nonexistent").toString());
        assertEquals(List.of(), ClaudeSessions.listSessions());
        assertEquals(List.of(), ClaudeSessions.listSessions(projectPath));
    }

    @Test
    void singleSessionMetadata() throws IOException {
        String sid = newId();
        write(projectDir, sid, jsonl(List.of(
                Json.obj("type", "user", "cwd", "/work", "gitBranch", "main",
                        "timestamp", "2026-01-15T10:30:00.000Z", "message", Json.obj("content", "Hello Claude")),
                Json.obj("type", "assistant", "message", Json.obj("content", "Hi")))));

        List<SessionInfo> sessions = listProject();

        assertEquals(1, sessions.size());
        SessionInfo info = sessions.getFirst();
        assertEquals(sid, info.sessionId());
        assertEquals("Hello Claude", info.summary());
        assertEquals("Hello Claude", info.firstPrompt());
        assertEquals("main", info.gitBranch());
        assertEquals("/work", info.cwd());
        assertEquals(Files.size(projectDir.resolve(sid + ".jsonl")), info.fileSize());
        assertTrue(info.lastModified() > 0);
        assertEquals(java.time.Instant.parse("2026-01-15T10:30:00Z").toEpochMilli(), info.createdAt());
        assertNull(info.customTitle());
        assertNull(info.tag());
    }

    @Test
    void customTitleWinsOverSummaryWhichWinsOverFirstPrompt() throws IOException {
        Path titled = write(projectDir, newId(),
                session("prompt", "type", "summary", "summary", "auto", "customTitle", "mine"));
        assertEquals("mine", listProject().getFirst().summary());
        Files.delete(titled);
        write(projectDir, newId(), session("prompt", "type", "summary", "summary", "auto"));
        assertEquals("auto", listProject().getFirst().summary());
        assertEquals("prompt", listProject().getFirst().firstPrompt());
    }

    @Test
    void cwdFallsBackToProjectPath() throws IOException {
        write(projectDir, newId(), session("hi"));
        assertEquals(SessionPaths.canonicalizePath(projectPath), listProject().getFirst().cwd());
    }

    @Test
    void sortedByMtimeWithLimitAndOffset() throws IOException {
        String[] ids = new String[5];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = newId();
            touch(write(projectDir, ids[i], session("prompt " + i)), 1_000_000_000_000L + i * 1000L);
        }
        assertEquals(List.of(ids[4], ids[3], ids[2], ids[1], ids[0]),
                listProject().stream().map(SessionInfo::sessionId).toList());
        assertEquals(List.of(ids[4], ids[3]), ClaudeSessions.listSessions(projectPath, 2, 0, false).stream()
                .map(SessionInfo::sessionId).toList());
        assertEquals(List.of(ids[2], ids[1]), ClaudeSessions.listSessions(projectPath, 2, 2, false).stream()
                .map(SessionInfo::sessionId).toList());
        assertEquals(List.of(ids[0]), ClaudeSessions.listSessions(projectPath, 10, 4, false).stream()
                .map(SessionInfo::sessionId).toList());
        assertEquals(5, ClaudeSessions.listSessions(projectPath, 0, 0, false).size());
    }

    @Test
    void filtersSidechainMetaOnlyEmptyNonUuidAndNonJsonlFiles() throws IOException {
        String keep = newId();
        write(projectDir, keep, session("keep"));
        write(projectDir, newId(), jsonl(List.of(Json.obj("type", "user", "isSidechain", true,
                "message", Json.obj("content", "side")))));
        write(projectDir, newId(), jsonl(List.of(Json.obj("type", "user", "isMeta", true,
                "message", Json.obj("content", "meta")))));
        write(projectDir, newId(), "");
        write(projectDir, "not-a-uuid", session("bad name"));
        Files.writeString(projectDir.resolve(newId() + ".json"), session("wrong ext"));

        assertEquals(List.of(keep), listProject().stream().map(SessionInfo::sessionId).toList());
    }

    @Test
    void listAllProjectsDeduplicatesKeepingNewest() throws IOException {
        Path other = projectDirFor(Files.createDirectories(tmp.resolve("other")).toString());
        String shared = newId();
        String unique = newId();
        touch(write(projectDir, shared, session("old copy")), 1_000_000_000_000L);
        touch(write(other, shared, session("new copy")), 1_000_000_005_000L);
        write(other, unique, session("unique"));

        List<SessionInfo> all = ClaudeSessions.listSessions();

        assertEquals(2, all.size());
        assertEquals("new copy", all.stream().filter(s -> s.sessionId().equals(shared)).findFirst()
                .orElseThrow().summary());
    }

    @Test
    void unknownProjectDirectoryListsNothing() throws IOException {
        write(projectDir, newId(), session("x"));
        assertEquals(List.of(), ClaudeSessions.listSessions(tmp.resolve("nope").toString(), 0, 0, false));
    }

    private static boolean git(Path dir, String... args) {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    void includesGitWorktreeSessions() throws IOException {
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        org.junit.jupiter.api.Assumptions.assumeTrue(git(repo, "init", "-q")
                && git(repo, "commit", "-q", "--allow-empty", "-m", "init")
                && git(repo, "worktree", "add", "-q", tmp.resolve("wt").toString()), "git unavailable");
        String inRepo = newId();
        String inWorktree = newId();
        write(projectDirFor(repo.toString()), inRepo, session("repo"));
        write(projectDirFor(tmp.resolve("wt").toString()), inWorktree, session("worktree"));

        assertEquals(java.util.Set.of(inRepo, inWorktree), java.util.Set.copyOf(
                ClaudeSessions.listSessions(repo.toString()).stream().map(SessionInfo::sessionId).toList()));
        assertEquals(List.of(inRepo), ClaudeSessions.listSessions(repo.toString(), 0, 0, false).stream()
                .map(SessionInfo::sessionId).toList());
        SessionInfo fromWorktree = ClaudeSessions.getSessionInfo(inWorktree, repo.toString()).orElseThrow();
        assertEquals("worktree", fromWorktree.summary());
        assertEquals(SessionPaths.canonicalizePath(tmp.resolve("wt").toString()), fromWorktree.cwd());
    }

    @Test
    void tagExtraction() throws IOException {
        String sid = newId();
        write(projectDir, sid, jsonl(List.of(
                Json.obj("type", "user", "message", Json.obj("content", "tag this v1.0")),
                Json.obj("type", "tag", "tag", "real-tag", "sessionId", sid),
                Json.obj("type", "assistant", "message", Json.obj("content", List.of(
                        Json.obj("type", "tool_use", "name", "docker", "input", Json.obj("tag", "myapp:v2"))))))));
        assertEquals("real-tag", listProject().getFirst().tag());
        assertEquals("real-tag", ClaudeSessions.getSessionInfo(sid, projectPath).orElseThrow().tag());
    }

    // ---------------------------------------------------------------- getSessionInfo

    @Test
    void getSessionInfoLookups() throws IOException {
        String sid = newId();
        write(projectDir, sid, session("hello"));
        String side = newId();
        write(projectDir, side, jsonl(List.of(Json.obj("type", "user", "isSidechain", true,
                "message", Json.obj("content", "side")))));

        assertTrue(ClaudeSessions.getSessionInfo("not-a-uuid").isEmpty());
        assertTrue(ClaudeSessions.getSessionInfo(newId()).isEmpty());
        assertEquals("hello", ClaudeSessions.getSessionInfo(sid, projectPath).orElseThrow().summary());
        SessionInfo anyProject = ClaudeSessions.getSessionInfo(sid).orElseThrow();
        assertEquals("hello", anyProject.summary());
        assertNull(anyProject.cwd()); // no project path without a directory
        assertTrue(ClaudeSessions.getSessionInfo(side, projectPath).isEmpty());
        String elsewhere = Files.createDirectories(tmp.resolve("elsewhere")).toString();
        assertTrue(ClaudeSessions.getSessionInfo(sid, elsewhere).isEmpty());
    }

    // ---------------------------------------------------------------- getSessionMessages

    @Test
    void getSessionMessagesReadsTheChain() throws IOException {
        String sid = newId();
        write(projectDir, sid, "garbage line\n" + jsonl(List.of(
                entry("user", "u1", null, sid, "hello"),
                entry("assistant", "a1", "u1", sid, "hi!"),
                entry("user", "u2", "a1", sid, "thanks"),
                entry("assistant", "a2", "u2", sid, "welcome"))));

        List<SessionMessage> messages = ClaudeSessions.getSessionMessages(sid, projectPath);

        assertEquals(List.of("u1", "a1", "u2", "a2"), messages.stream().map(SessionMessage::uuid).toList());
        assertEquals(List.of("user", "assistant", "user", "assistant"),
                messages.stream().map(SessionMessage::type).toList());
        assertEquals(sid, messages.getFirst().sessionId());
        assertEquals(Map.of("role", "user", "content", "hello"), messages.getFirst().message());
        assertNull(messages.getFirst().parentToolUseId());
        assertEquals(List.of("a1", "u2"), ClaudeSessions.getSessionMessages(sid, projectPath, 2, 1).stream()
                .map(SessionMessage::uuid).toList());
        assertEquals(4, ClaudeSessions.getSessionMessages(sid).size()); // searches all projects
        assertEquals(List.of(), ClaudeSessions.getSessionMessages("not-a-uuid"));
        assertEquals(List.of(), ClaudeSessions.getSessionMessages(newId()));
    }

    // ---------------------------------------------------------------- subagents

    private Path subagentsDir(String sid) throws IOException {
        return Files.createDirectories(projectDir.resolve(sid).resolve("subagents"));
    }

    private static String agentTranscript(String sid) {
        return jsonl(List.of(entry("user", "su1", null, sid, "task"), entry("assistant", "sa1", "su1", sid, "done")));
    }

    @Test
    void listSubagentsScansRecursively() throws IOException {
        String sid = newId();
        assertEquals(List.of(), ClaudeSessions.listSubagents(sid, projectPath)); // no session
        write(projectDir, sid, session("main"));
        assertEquals(List.of(), ClaudeSessions.listSubagents(sid, projectPath)); // no subagents dir
        Path subagents = subagentsDir(sid);
        Files.writeString(subagents.resolve("agent-b.jsonl"), agentTranscript(sid));
        Files.writeString(subagents.resolve("agent-a.jsonl"), agentTranscript(sid));
        Files.writeString(subagents.resolve("agent-a.meta.json"), "{}");
        Files.writeString(subagents.resolve("notes.txt"), "x");
        Path nested = Files.createDirectories(subagents.resolve("workflows").resolve("run-1"));
        Files.writeString(nested.resolve("agent-nested.jsonl"), agentTranscript(sid));

        assertEquals(List.of("a", "b", "nested"), ClaudeSessions.listSubagents(sid, projectPath));
        assertEquals(List.of("a", "b", "nested"), ClaudeSessions.listSubagents(sid));
        assertEquals(List.of(), ClaudeSessions.listSubagents("not-a-uuid"));
    }

    @Test
    void getSubagentMessagesWithParentIdsFromSidecar() throws IOException {
        String sid = newId();
        write(projectDir, sid, session("main"));
        Path subagents = subagentsDir(sid);
        Files.writeString(subagents.resolve("agent-x.jsonl"), "corrupt\n" + agentTranscript(sid));
        Files.writeString(subagents.resolve("agent-x.meta.json"),
                Json.write(Json.obj("agentType", "gp", "toolUseId", "toolu_1", "parentAgentId", "p1")));
        Path nested = Files.createDirectories(subagents.resolve("workflows").resolve("run-1"));
        Files.writeString(nested.resolve("agent-n.jsonl"), agentTranscript(sid));
        Files.writeString(nested.resolve("agent-n.meta.json"), "not json");

        List<SessionMessage> messages = ClaudeSessions.getSubagentMessages(sid, "x", projectPath);
        assertEquals(List.of("su1", "sa1"), messages.stream().map(SessionMessage::uuid).toList());
        assertTrue(messages.stream().allMatch(m -> "toolu_1".equals(m.parentToolUseId())
                && "p1".equals(m.parentAgentId())));
        assertEquals(List.of("sa1"), ClaudeSessions.getSubagentMessages(sid, "x", projectPath, 1, 1).stream()
                .map(SessionMessage::uuid).toList());

        List<SessionMessage> nestedMessages = ClaudeSessions.getSubagentMessages(sid, "n");
        assertEquals(2, nestedMessages.size());
        assertNull(nestedMessages.getFirst().parentToolUseId());

        assertEquals(List.of(), ClaudeSessions.getSubagentMessages(sid, "missing", projectPath));
        assertEquals(List.of(), ClaudeSessions.getSubagentMessages(sid, "", projectPath));
        assertEquals(List.of(), ClaudeSessions.getSubagentMessages("not-a-uuid", "x"));
    }

    @Test
    void projectKeyMatchesProjectDirectoryName() {
        assertEquals(projectDir.getFileName().toString(), ClaudeSessions.projectKeyForDirectory(projectPath));
        assertEquals(ClaudeSessions.projectKeyForDirectory("."), ClaudeSessions.projectKeyForDirectory());
        String longPath = "/" + "x".repeat(300);
        String key = ClaudeSessions.projectKeyForDirectory(longPath);
        assertEquals(SessionPaths.MAX_SANITIZED_LENGTH + 1 + SessionPaths.simpleHash(longPath).length(),
                key.length());
    }
}
