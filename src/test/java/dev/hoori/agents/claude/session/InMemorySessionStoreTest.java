package dev.hoori.agents.claude.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.session.ClaudeSessionsFromStoreTest.MinimalStore;

/** {@link InMemorySessionStore} and the {@link SessionStoreConformance} harness. */
class InMemorySessionStoreTest {

    private static final SessionKey KEY = SessionKey.of("proj", "sess");

    @Test
    void passesConformance() throws Exception {
        SessionStoreConformance.run(InMemorySessionStore::new);
    }

    @Test
    void minimalStorePassesWithOptionalsSkippedOrAutoDetected() throws Exception {
        SessionStoreConformance.run(MinimalStore::new, Set.of("listSessions", "delete", "listSubkeys"));
        SessionStoreConformance.run(MinimalStore::new);
    }

    @Test
    void rejectsUnknownSkipNames() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionStoreConformance.run(InMemorySessionStore::new, Set.of("list_sessions")));
    }

    @Test
    void detectsBrokenAdapters() {
        // Declares delete() but does not cascade to subkeys.
        AssertionError cascade = assertThrows(AssertionError.class, () -> SessionStoreConformance.run(
                () -> new InMemorySessionStore() {
                    @Override
                    public synchronized void delete(SessionKey key) {
                        super.delete(new SessionKey(key.projectKey(), key.sessionId(),
                                key.subpath() == null ? "none" : key.subpath()));
                    }
                }));
        assertTrue(cascade.getMessage().contains("delete"), cascade.getMessage());
        // Reports epoch seconds.
        AssertionError seconds = assertThrows(AssertionError.class, () -> SessionStoreConformance.run(
                () -> new InMemorySessionStore() {
                    @Override
                    public synchronized List<SessionStoreListEntry> listSessions(String projectKey) {
                        return super.listSessions(projectKey).stream()
                                .map(e -> new SessionStoreListEntry(e.sessionId(), e.mtime() / 1000)).toList();
                    }
                }));
        assertTrue(seconds.getMessage().contains("7."), seconds.getMessage());
    }

    @Test
    void implementsEveryOptionalMethod() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionStoreConformance.OPTIONAL_METHODS
                .forEach(m -> assertTrue(SessionStores.implementsMethod(store, m), m));
        assertFalse(SessionStores.implementsMethod(new MinimalStore(), "listSessions"));
    }

    @Test
    void testHelpers() {
        InMemorySessionStore store = new InMemorySessionStore();
        assertEquals(List.of(), store.getEntries(KEY));
        store.append(KEY, List.of(Map.of("n", 1), Map.of("n", 2)));
        store.getEntries(KEY).add(Map.of("n", 999));
        assertEquals(List.of(Map.of("n", 1), Map.of("n", 2)), store.getEntries(KEY));
        store.load(KEY).add(Map.of("n", 999));
        assertEquals(2, store.load(KEY).size());

        store.append(SessionKey.of("proj", "b"), List.of(Map.of("n", 1)));
        store.append(new SessionKey("proj", "sess", "sub/x"), List.of(Map.of("n", 1)));
        assertEquals(2, store.size());

        store.clear();
        assertEquals(0, store.size());
        assertNull(store.load(KEY));
        assertEquals(List.of(), store.listSessions("proj"));
    }

    @Test
    void summariesTrackMainTranscriptsPerProject() {
        InMemorySessionStore store = new InMemorySessionStore();
        SessionKey a = SessionKey.of("P", "a");
        store.append(a, List.of(prompt("hello a")));
        store.append(a, List.of(Map.of("type", "x", "customTitle", "Title A")));
        store.append(new SessionKey("P", "a", "subagents/agent-1"),
                List.of(prompt("sub"), Map.of("type", "x", "customTitle", "sub")));
        store.append(SessionKey.of("P", "b"), List.of(prompt("hello b")));
        store.append(SessionKey.of("Q", "a"), List.of(prompt("other project")));

        Map<String, SessionSummaryEntry> byId = new java.util.HashMap<>();
        store.listSessionSummaries("P").forEach(s -> byId.put(s.sessionId(), s));
        assertEquals(Set.of("a", "b"), byId.keySet());
        assertEquals("Title A", byId.get("a").data().get("custom_title"));
        assertEquals("hello a", byId.get("a").data().get("first_prompt"));
        assertEquals(1, store.listSessionSummaries("Q").size());
        assertEquals(List.of(), store.listSessionSummaries("R"));

        store.delete(a);
        assertEquals(List.of("b"), store.listSessionSummaries("P").stream().map(SessionSummaryEntry::sessionId)
                .toList());
    }

    @Test
    void mtimesAreStrictlyIncreasingAndAppendsAreThreadSafe() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                int n = i;
                executor.submit(() -> store.append(SessionKey.of("p", "s" + (n % 10)), List.of(Map.of("n", n))));
            }
        }
        List<Long> mtimes = new ArrayList<>(store.listSessions("p").stream().map(SessionStoreListEntry::mtime)
                .toList());
        assertEquals(10, Set.copyOf(mtimes).size());
        assertEquals(200, store.listSessions("p").stream()
                .mapToInt(e -> store.load(SessionKey.of("p", e.sessionId())).size()).sum());
    }

    private static Map<String, Object> prompt(String text) {
        return Map.of("type", "user", "message", Map.of("role", "user", "content", text));
    }
}
