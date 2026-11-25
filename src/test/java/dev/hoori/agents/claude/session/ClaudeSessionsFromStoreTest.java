package dev.hoori.agents.claude.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.session.SessionPaths;
import dev.hoori.agents.claude.internal.session.TranscriptFixtures;
import dev.hoori.agents.claude.internal.session.TranscriptFixtures.ParallelTurn;

/** The {@code *FromStore} read functions, against {@link InMemorySessionStore} and custom adapters. */
class ClaudeSessionsFromStoreTest {

    static final String DIR = "/workspace/project";
    static final String PROJECT_KEY = ClaudeSessions.projectKeyForDirectory(DIR);

    static String newId() {
        return UUID.randomUUID().toString();
    }

    static SessionKey key(String sid) {
        return SessionKey.of(PROJECT_KEY, sid);
    }

    static Map<String, Object> user(String text, String uuid, String parent, String sid) {
        return TranscriptFixtures.entry("user", uuid, parent, sid, text, "timestamp", "2024-01-01T00:00:00.000Z");
    }

    static Map<String, Object> assistant(String text, String uuid, String parent, String sid) {
        return TranscriptFixtures.entry("assistant", uuid, parent, sid,
                List.of(Json.obj("type", "text", "text", text)), "timestamp", "2024-01-01T00:00:01.000Z");
    }

    /** A user prompt with only a timestamp (the summary-test shape). */
    static Map<String, Object> prompt(String text, String ts) {
        return Json.obj("type", "user", "timestamp", ts, "message", Json.obj("role", "user", "content", text));
    }

    /** Appends {@code n} user/assistant pairs and returns their uuids in order. */
    static List<String> seedChain(SessionStore store, String sid, int n) throws Exception {
        List<String> uuids = new ArrayList<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        String parent = null;
        for (int i = 0; i < n; i++) {
            String u = newId();
            String a = newId();
            entries.add(user("prompt " + i, u, parent, sid));
            entries.add(assistant("reply " + i, a, u, sid));
            uuids.add(u);
            uuids.add(a);
            parent = a;
        }
        store.append(key(sid), entries);
        return uuids;
    }

    static List<String> ids(List<SessionInfo> sessions) {
        return sessions.stream().map(SessionInfo::sessionId).toList();
    }

    /** Implements only the required methods. */
    static class MinimalStore implements SessionStore {
        private final Map<String, List<Map<String, Object>>> data = new HashMap<>();

        private static String k(SessionKey key) {
            return key.projectKey() + "/" + key.sessionId() + "/" + (key.subpath() == null ? "" : key.subpath());
        }

        @Override
        public void append(SessionKey key, List<Map<String, Object>> entries) {
            data.computeIfAbsent(k(key), x -> new ArrayList<>()).addAll(entries);
        }

        @Override
        public List<Map<String, Object>> load(SessionKey key) {
            return data.get(k(key));
        }
    }

    /** Suppresses the summary fast path. */
    static class SlowPathStore extends InMemorySessionStore {
        @Override
        public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
            throw new UnsupportedOperationException();
        }
    }

    /** Records the session ids passed to load(). */
    static class CountingStore extends InMemorySessionStore {
        final List<String> loadCalls = Collections.synchronizedList(new ArrayList<>());

        @Override
        public List<Map<String, Object>> load(SessionKey key) {
            loadCalls.add(key.sessionId());
            return super.load(key);
        }
    }

    // ---------------------------------------------------------------- listSessionsFromStore (load path)

    @Nested
    class ListSessions {

        @Test
        void listsSeededSessionsNewestFirst() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String a = newId();
            String b = newId();
            seedChain(store, a, 2);
            seedChain(store, b, 2);

            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);

            assertEquals(Set.of(a, b), Set.copyOf(ids(sessions)));
            assertTrue(sessions.stream().allMatch(s -> s.summary().equals("prompt 0")
                    && s.firstPrompt().equals("prompt 0")));
            assertTrue(sessions.get(0).lastModified() >= sessions.get(1).lastModified());
            SlowPathStore slow = new SlowPathStore();
            for (int i = 0; i < 3; i++) {
                seedChain(slow, newId(), 1);
            }
            assertEquals(2, ClaudeSessions.listSessionsFromStore(slow, DIR, 2, 1).size());
        }

        @Test
        void requiresAListingMethod() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ClaudeSessions.listSessionsFromStore(new MinimalStore(), DIR));
            assertTrue(e.getMessage().contains("listSessions"));
        }

        @Test
        void sidechainsAreFilteredBeforePaging() throws Exception {
            SlowPathStore store = new SlowPathStore();
            List<String> valid = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                String sid = newId();
                seedChain(store, sid, 1);
                valid.add(sid);
            }
            for (int i = 0; i < 3; i++) {
                String sc = newId();
                Map<String, Object> entry = user("sidechain", newId(), null, sc);
                entry.put("isSidechain", true);
                store.append(key(sc), List.of(entry));
            }
            List<SessionInfo> page = ClaudeSessions.listSessionsFromStore(store, DIR, 5, 0);
            assertEquals(Set.copyOf(valid), Set.copyOf(ids(page)));
            List<SessionInfo> page2 = ClaudeSessions.listSessionsFromStore(store, DIR, 5, 2);
            assertEquals(3, page2.size());
            assertTrue(valid.containsAll(ids(page2)));
            assertTrue(ClaudeSessions.listSessionsFromStore(store, DIR).stream().noneMatch(s -> s.summary().isEmpty()));
        }

        @Test
        void doesNotMutateTheAdapterListing() {
            List<SessionStoreListEntry> internal = new ArrayList<>(List.of(
                    new SessionStoreListEntry("a", 1), new SessionStoreListEntry("b", 2)));
            SessionStore store = new MinimalStore() {
                @Override
                public List<SessionStoreListEntry> listSessions(String projectKey) {
                    return internal;
                }
            };
            ClaudeSessions.listSessionsFromStore(store, DIR);
            assertEquals(List.of("a", "b"), internal.stream().map(SessionStoreListEntry::sessionId).toList());
        }

        @Test
        void failingLoadDegradesOnlyThatRow() throws Exception {
            String good = newId();
            String bad = newId();
            SlowPathStore store = new SlowPathStore() {
                @Override
                public List<Map<String, Object>> load(SessionKey key) {
                    if (key.sessionId().equals(bad)) {
                        throw new IllegalStateException("backend down");
                    }
                    return super.load(key);
                }
            };
            seedChain(store, good, 1);
            seedChain(store, bad, 1);

            Map<String, SessionInfo> byId = ClaudeSessions.listSessionsFromStore(store, DIR).stream()
                    .collect(Collectors.toMap(SessionInfo::sessionId, s -> s));

            assertEquals("prompt 0", byId.get(good).summary());
            assertEquals("", byId.get(bad).summary());
            assertTrue(byId.get(bad).lastModified() > 0);
        }

        @Test
        void loadConcurrencyIsBoundedTo16() throws Exception {
            assertBoundedConcurrency(new SlowPathStore() {
                @Override
                public List<Map<String, Object>> load(SessionKey key) {
                    return gatedLoad(() -> super.load(key));
                }
            });
        }

        @Test
        void gapFillConcurrencyIsBoundedTo16() throws Exception {
            assertBoundedConcurrency(new InMemorySessionStore() {
                @Override
                public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
                    return List.of(); // everything is "missing"
                }

                @Override
                public List<Map<String, Object>> load(SessionKey key) {
                    return gatedLoad(() -> super.load(key));
                }
            });
        }
    }

    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();
    private static final AtomicReference<CountDownLatch> GATE = new AtomicReference<>();

    private static List<Map<String, Object>> gatedLoad(Supplier<List<Map<String, Object>>> load) {
        PEAK.accumulateAndGet(IN_FLIGHT.incrementAndGet(), Math::max);
        try {
            GATE.get().await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        IN_FLIGHT.decrementAndGet();
        return load.get();
    }

    private static void assertBoundedConcurrency(InMemorySessionStore store) throws Exception {
        IN_FLIGHT.set(0);
        PEAK.set(0);
        GATE.set(new CountDownLatch(1));
        int n = 16 * 3;
        for (int i = 0; i < n; i++) {
            store.append(key(newId()), List.of(Json.obj("type", "user", "uuid", "u" + i)));
        }
        AtomicReference<List<SessionInfo>> result = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> result.set(ClaudeSessions.listSessionsFromStore(store, DIR)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (IN_FLIGHT.get() < 16 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        Thread.sleep(100); // give an unbounded implementation the chance to exceed the bound
        int peakAtSaturation = PEAK.get();
        GATE.get().countDown();
        runner.join(10_000);
        assertEquals(16, peakAtSaturation);
        assertEquals(16, PEAK.get());
        assertTrue(result.get().size() <= n);
    }

    // ---------------------------------------------------------------- summary fast path

    @Nested
    class FastPath {

        @Test
        void fastPathSkipsLoad() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore() {
                @Override
                public List<Map<String, Object>> load(SessionKey key) {
                    throw new AssertionError("load() must not be called on the fast path");
                }
            };
            String a = newId();
            String b = newId();
            store.append(key(a), List.of(withCwd(prompt("first a", "2024-01-01T00:00:00Z"))));
            store.append(key(b), List.of(withCwd(prompt("first b", "2024-01-02T00:00:00Z"))));

            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);

            assertEquals(List.of(b, a), ids(sessions));
            assertEquals("first b", sessions.get(0).summary());
            assertEquals("first a", sessions.get(1).firstPrompt());
            assertNull(sessions.get(0).fileSize());
        }

        private static Map<String, Object> withCwd(Map<String, Object> entry) {
            entry.put("cwd", DIR);
            return entry;
        }

        @Test
        void filtersSidechainAndEmptySessions() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String main = newId();
            store.append(key(main), List.of(prompt("hello", "2024-01-01T00:00:00Z")));
            store.append(key(newId()), List.of(Json.obj("type", "user", "timestamp", "2024-01-01T00:00:00Z",
                    "isSidechain", true, "message", Json.obj("content", "x"))));
            store.append(key(newId()), List.of(Json.obj("type", "x", "timestamp", "2024-01-01T00:00:00Z")));
            assertEquals(List.of(main), ids(ClaudeSessions.listSessionsFromStore(store, DIR)));
        }

        @Test
        void limitAndOffset() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            List<String> sids = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                sids.add(newId());
                store.append(key(sids.get(i)), List.of(prompt("p" + i, "2024-01-0" + (i + 1) + "T00:00:00Z")));
            }
            assertEquals(List.of(sids.get(3), sids.get(2)), ids(ClaudeSessions.listSessionsFromStore(store, DIR, 2, 1)));
        }

        @Test
        void unsupportedSummariesFallBackToLoad() throws Exception {
            SlowPathStore store = new SlowPathStore();
            store.append(key(newId()), List.of(prompt("hi", "2024-01-01T00:00:00Z")));
            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);
            assertEquals(1, sessions.size());
            assertEquals("hi", sessions.getFirst().summary());
        }

        @Test
        void sessionsWithoutSummaryAreGapFilled() throws Exception {
            String with = newId();
            String without = newId();
            CountingStore store = new CountingStore() {
                @Override
                public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
                    return super.listSessionSummaries(projectKey).stream()
                            .filter(s -> s.sessionId().equals(with)).toList();
                }
            };
            store.append(key(with), List.of(prompt("has sidecar", "2024-01-02T00:00:00Z")));
            store.append(key(without), List.of(prompt("no sidecar", "2024-01-01T00:00:00Z")));

            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);

            assertEquals(List.of(without, with), ids(sessions)); // storage mtime: appended last sorts first
            assertEquals("no sidecar", sessions.get(0).summary());
            assertEquals("has sidecar", sessions.get(1).summary());
            assertEquals(List.of(without), store.loadCalls);
        }

        @Test
        void gapFillLoadsAreBoundedByThePage() throws Exception {
            String with = newId();
            CountingStore store = new CountingStore() {
                @Override
                public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
                    return super.listSessionSummaries(projectKey).stream()
                            .filter(s -> s.sessionId().equals(with)).toList();
                }
            };
            for (int i = 0; i < 5; i++) {
                store.append(key(newId()), List.of(prompt("without " + i, "2024-01-0" + (i + 1) + "T00:00:00Z")));
            }
            store.append(key(with), List.of(prompt("with", "2024-01-10T00:00:00Z")));

            List<SessionInfo> page = ClaudeSessions.listSessionsFromStore(store, DIR, 2, 0);

            assertEquals(2, page.size());
            assertEquals(with, page.getFirst().sessionId());
            assertEquals(1, store.loadCalls.size());
        }

        @Test
        void sidechainSummaryDoesNotConsumeAPageSlot() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String real2 = newId();
            String real1 = newId();
            store.append(key(real2), List.of(prompt("real 2", "2024-01-01T00:00:00Z")));
            store.append(key(real1), List.of(prompt("real 1", "2024-01-02T00:00:00Z")));
            store.append(key(newId()), List.of(Json.obj("type", "user", "timestamp", "2024-01-03T00:00:00Z",
                    "isSidechain", true, "message", Json.obj("content", "x"))));
            assertEquals(List.of(real1, real2), ids(ClaudeSessions.listSessionsFromStore(store, DIR, 2, 0)));
        }

        @Test
        void staleSummaryIsRederivedFromSource() throws Exception {
            String sid = newId();
            long stale = 1_704_067_260_000L;
            long fresh = 1_704_153_660_000L;
            CountingStore store = new CountingStore() {
                @Override
                public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
                    return List.of(new SessionSummaryEntry(sid, stale, Json.obj("custom_title", "old",
                            "first_prompt", "old prompt", "first_prompt_locked", true, "created_at", stale)));
                }
            };
            store.append(key(sid), List.of(prompt("fresh prompt", "2024-01-02T00:00:00Z"),
                    Json.obj("type", "x", "timestamp", "2024-01-02T00:01:00Z", "customTitle", "fresh")));

            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);

            assertEquals(1, sessions.size());
            assertEquals("fresh", sessions.getFirst().customTitle());
            assertEquals("fresh", sessions.getFirst().summary());
            assertTrue(sessions.getFirst().lastModified() >= fresh);
            assertEquals(List.of(sid), store.loadCalls);
        }

        @Test
        void freshSummaryOnTheStorageClockIsNotGapFilled() throws Exception {
            long t2 = 1_704_067_200_250L;
            CountingStore store = new CountingStore() {
                @Override
                public List<SessionStoreListEntry> listSessions(String projectKey) {
                    return super.listSessions(projectKey).stream()
                            .map(e -> new SessionStoreListEntry(e.sessionId(), t2)).toList();
                }

                @Override
                public List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
                    return super.listSessionSummaries(projectKey).stream()
                            .map(s -> new SessionSummaryEntry(s.sessionId(), t2, s.data())).toList();
                }
            };
            store.append(key(newId()), List.of(prompt("fresh prompt", "2024-01-01T00:00:00.000Z"),
                    Json.obj("type", "x", "timestamp", "2024-01-01T00:00:00.000Z", "customTitle", "fresh")));

            List<SessionInfo> sessions = ClaudeSessions.listSessionsFromStore(store, DIR);

            assertEquals("fresh", sessions.getFirst().summary());
            assertEquals(t2, sessions.getFirst().lastModified());
            assertEquals(List.of(), store.loadCalls);
        }

        @Test
        void summaryWithoutListingIsDropped() throws Exception {
            String real = newId();
            String ghost = newId();
            InMemorySessionStore store = new InMemorySessionStore() {
                @Override
                public List<SessionStoreListEntry> listSessions(String projectKey) {
                    return super.listSessions(projectKey).stream()
                            .filter(e -> !e.sessionId().equals(ghost)).toList();
                }
            };
            store.append(key(real), List.of(prompt("real", "2024-01-02T00:00:00Z")));
            store.append(key(ghost), List.of(prompt("ghost", "2024-01-01T00:00:00Z")));
            assertEquals(List.of(real), ids(ClaudeSessions.listSessionsFromStore(store, DIR)));
        }
    }

    // ---------------------------------------------------------------- getSessionInfoFromStore / messages

    @Nested
    class SingleSession {

        @Test
        void sessionInfo() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            seedChain(store, sid, 1);

            SessionInfo info = ClaudeSessions.getSessionInfoFromStore(store, sid, DIR).orElseThrow();
            assertEquals(sid, info.sessionId());
            assertEquals("prompt 0", info.summary());
            assertEquals(1_704_067_200_000L, info.createdAt());
            assertEquals(1_704_067_201_000L, info.lastModified()); // last entry's timestamp
            assertEquals(SessionPaths.canonicalizePath(DIR), info.cwd());
            assertEquals(SessionPaths.canonicalizePath(DIR),
                    ClaudeSessions.listSessionsFromStore(store, DIR).getFirst().cwd());

            store.append(key(sid), List.of(Json.obj("type", "custom-title", "customTitle", "My Title",
                    "sessionId", sid)));
            store.append(key(sid), List.of(Json.obj("sessionId", sid, "tag", "exp", "type", "tag")));
            SessionInfo titled = ClaudeSessions.getSessionInfoFromStore(store, sid, DIR).orElseThrow();
            assertEquals("My Title", titled.summary());
            assertEquals("My Title", titled.customTitle());
            assertEquals("exp", titled.tag()); // type is hoisted to the line start

            assertTrue(ClaudeSessions.getSessionInfoFromStore(store, newId(), DIR).isEmpty());
            assertTrue(ClaudeSessions.getSessionInfoFromStore(store, "not-a-uuid", DIR).isEmpty());
        }

        @Test
        void sessionMessages() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            List<String> uuids = seedChain(store, sid, 3);
            store.append(key(sid), List.of(Json.obj("type", "custom-title", "customTitle", "Title"),
                    Json.obj("type", "tag", "tag", "exp")));

            List<SessionMessage> messages = ClaudeSessions.getSessionMessagesFromStore(store, sid, DIR);
            assertEquals(uuids, messages.stream().map(SessionMessage::uuid).toList());
            assertEquals("user", messages.get(0).type());
            assertEquals("assistant", messages.get(1).type());
            assertTrue(messages.stream().allMatch(m -> m.parentToolUseId() == null && m.parentAgentId() == null));
            assertEquals(uuids.subList(2, 4), ClaudeSessions.getSessionMessagesFromStore(store, sid, DIR, 2, 2)
                    .stream().map(SessionMessage::uuid).toList());
            assertEquals(List.of(), ClaudeSessions.getSessionMessagesFromStore(store, newId(), DIR));
        }

        @Test
        void parallelToolCallsKeepEveryCallAndResult() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            ParallelTurn turn = TranscriptFixtures.parallelToolTurn(sid, "prompt", 3, 0, 1, 2);
            List<Map<String, Object>> entries = new ArrayList<>();
            entries.add(user("read them", "prompt", null, sid));
            entries.addAll(turn.calls());
            entries.addAll(turn.results());
            entries.add(assistant("done", "answer", "result-0", sid));
            store.append(key(sid), entries);

            assertEquals(List.of("prompt", "call-0", "call-1", "call-2", "result-1", "result-2", "result-0", "answer"),
                    ClaudeSessions.getSessionMessagesFromStore(store, sid, DIR).stream()
                            .map(SessionMessage::uuid).toList());
        }

        @Test
        void checkedAdapterExceptionsAreWrapped() {
            SessionStore store = new SessionStore() {
                @Override
                public void append(SessionKey key, List<Map<String, Object>> entries) {
                }

                @Override
                public List<Map<String, Object>> load(SessionKey key) throws Exception {
                    throw new java.io.IOException("disk on fire");
                }
            };
            ClaudeSdkException e = assertThrows(ClaudeSdkException.class,
                    () -> ClaudeSessions.getSessionMessagesFromStore(store, newId(), DIR));
            assertTrue(e.getCause() instanceof java.io.IOException);
        }
    }

    // ---------------------------------------------------------------- subagents

    @Nested
    class Subagents {

        private SessionKey subKey(String sid, String subpath) {
            return new SessionKey(PROJECT_KEY, sid, subpath);
        }

        @Test
        void listAndReadSubagents() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            seedChain(store, sid, 1);
            String u = newId();
            String a = newId();
            store.append(subKey(sid, "subagents/agent-abc123"), List.of(user("sub prompt", u, null, sid),
                    assistant("sub reply", a, u, sid)));
            store.append(subKey(sid, "subagents/workflows/run-1/agent-nested"), List.of(user("hi", newId(), null, sid)));
            store.append(subKey(sid, "subagents/workflows/run-2/agent-abc123"), List.of(user("dup", newId(), null, sid)));

            assertEquals(List.of("abc123", "nested"), ClaudeSessions.listSubagentsFromStore(store, sid, DIR));
            List<SessionMessage> messages = ClaudeSessions.getSubagentMessagesFromStore(store, sid, "abc123", DIR);
            assertEquals(List.of(u, a), messages.stream().map(SessionMessage::uuid).toList());
            assertEquals(1, ClaudeSessions.getSubagentMessagesFromStore(store, sid, "nested", DIR).size());
            assertEquals(List.of(), ClaudeSessions.getSubagentMessagesFromStore(store, sid, "missing", DIR));
        }

        @Test
        void parentIdsFromTheLastAgentMetadataEntry() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            String u = newId();
            String a = newId();
            store.append(subKey(sid, "subagents/agent-x"), List.of(
                    Json.obj("type", "agent_metadata", "agentType", "gp", "toolUseId", "toolu_old"),
                    user("hi", u, null, sid),
                    assistant("hello", a, u, sid),
                    Json.obj("type", "agent_metadata", "agentType", "gp", "toolUseId", "toolu_new",
                            "parentAgentId", "a-parent")));

            List<SessionMessage> messages = ClaudeSessions.getSubagentMessagesFromStore(store, sid, "x", DIR);

            assertEquals(List.of(u, a), messages.stream().map(SessionMessage::uuid).toList());
            assertTrue(messages.stream().allMatch(m -> "toolu_new".equals(m.parentToolUseId())
                    && "a-parent".equals(m.parentAgentId())));
        }

        @Test
        void metadataOnlyOrNonStringMetadata() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            String sid = newId();
            store.append(subKey(sid, "subagents/agent-x"), List.of(
                    Json.obj("type", "agent_metadata", "toolUseId", 7), user("hi", newId(), null, sid)));
            store.append(subKey(sid, "subagents/agent-empty"), List.of(Json.obj("type", "agent_metadata")));

            List<SessionMessage> messages = ClaudeSessions.getSubagentMessagesFromStore(store, sid, "x", DIR);
            assertEquals(1, messages.size());
            assertNull(messages.getFirst().parentToolUseId());
            assertNull(messages.getFirst().parentAgentId());
            assertEquals(List.of(), ClaudeSessions.getSubagentMessagesFromStore(store, sid, "empty", DIR));
        }

        @Test
        void invalidInputsAndStoresWithoutListSubkeys() throws Exception {
            InMemorySessionStore store = new InMemorySessionStore();
            assertEquals(List.of(), ClaudeSessions.listSubagentsFromStore(store, "not-a-uuid", DIR));
            assertEquals(List.of(), ClaudeSessions.getSubagentMessagesFromStore(store, "not-a-uuid", "x", DIR));
            assertEquals(List.of(), ClaudeSessions.getSubagentMessagesFromStore(store, newId(), "", DIR));

            MinimalStore minimal = new MinimalStore();
            String sid = newId();
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ClaudeSessions.listSubagentsFromStore(minimal, sid, DIR));
            assertTrue(e.getMessage().contains("does not implement listSubkeys"));
            // Without listSubkeys the direct subagents/agent-<id> path is tried.
            minimal.append(subKey(sid, "subagents/agent-direct"), List.of(user("hi", newId(), null, sid)));
            assertEquals(1, ClaudeSessions.getSubagentMessagesFromStore(minimal, sid, "direct", DIR).size());
        }
    }
}
