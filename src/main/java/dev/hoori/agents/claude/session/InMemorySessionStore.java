package dev.hoori.agents.claude.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-safe in-memory {@link SessionStore} for tests and development; data is lost when the
 * process exits. Implements every optional method and maintains session summaries incrementally.
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<String, List<Map<String, Object>>> store = new LinkedHashMap<>();
    private final Map<String, Long> mtimes = new HashMap<>();
    private final Map<SummaryKey, SessionSummaryEntry> summaries = new LinkedHashMap<>();
    private long lastMtime;

    private record SummaryKey(String projectKey, String sessionId) {
    }

    private static String keyToString(SessionKey key) {
        String base = key.projectKey() + "/" + key.sessionId();
        return key.subpath() != null ? base + "/" + key.subpath() : base;
    }

    /** Storage write time in epoch ms, strictly increasing so back-to-back appends get distinct mtimes. */
    private long nextMtime() {
        lastMtime = Math.max(System.currentTimeMillis(), lastMtime + 1);
        return lastMtime;
    }

    @Override
    public synchronized void append(SessionKey key, List<Map<String, Object>> entries) {
        String k = keyToString(key);
        store.computeIfAbsent(k, x -> new ArrayList<>()).addAll(entries);
        long now = nextMtime();
        // Subagent transcripts don't contribute to the main session's summary.
        if (key.subpath() == null) {
            SummaryKey summaryKey = new SummaryKey(key.projectKey(), key.sessionId());
            SessionSummaryEntry folded = SessionSummaries.fold(summaries.get(summaryKey), key, entries);
            // Stamped with the same clock listSessions() exposes, as the summary contract requires.
            summaries.put(summaryKey, new SessionSummaryEntry(folded.sessionId(), now, folded.data()));
        }
        mtimes.put(k, now);
    }

    @Override
    public synchronized List<Map<String, Object>> load(SessionKey key) {
        List<Map<String, Object>> entries = store.get(keyToString(key));
        return entries == null ? null : new ArrayList<>(entries);
    }

    @Override
    public synchronized List<SessionStoreListEntry> listSessions(String projectKey) {
        String prefix = projectKey + "/";
        List<SessionStoreListEntry> results = new ArrayList<>();
        for (String k : store.keySet()) {
            // Main transcripts only: no further '/' after the project key.
            if (k.startsWith(prefix) && k.indexOf('/', prefix.length()) < 0) {
                results.add(new SessionStoreListEntry(k.substring(prefix.length()), mtimes.getOrDefault(k, 0L)));
            }
        }
        return results;
    }

    @Override
    public synchronized List<SessionSummaryEntry> listSessionSummaries(String projectKey) {
        return summaries.entrySet().stream()
                .filter(e -> e.getKey().projectKey().equals(projectKey))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public synchronized void delete(SessionKey key) {
        String k = keyToString(key);
        store.remove(k);
        mtimes.remove(k);
        // Deleting a main transcript cascades to its subkeys; a subpath delete removes only that key.
        if (key.subpath() == null) {
            summaries.remove(new SummaryKey(key.projectKey(), key.sessionId()));
            String prefix = k + "/";
            store.keySet().removeIf(sk -> sk.startsWith(prefix));
            mtimes.keySet().removeIf(sk -> sk.startsWith(prefix));
        }
    }

    @Override
    public synchronized List<String> listSubkeys(SessionListSubkeysKey key) {
        String prefix = key.projectKey() + "/" + key.sessionId() + "/";
        return store.keySet().stream().filter(k -> k.startsWith(prefix))
                .map(k -> k.substring(prefix.length())).toList();
    }

    // ---------------------------------------------------------------- test helpers

    /** All entries of a key (a copy; empty if absent). */
    public synchronized List<Map<String, Object>> getEntries(SessionKey key) {
        return new ArrayList<>(store.getOrDefault(keyToString(key), List.of()));
    }

    /** Number of stored main transcripts. */
    public synchronized int size() {
        return (int) store.keySet().stream().filter(k -> {
            int firstSlash = k.indexOf('/');
            return firstSlash >= 0 && k.indexOf('/', firstSlash + 1) < 0;
        }).count();
    }

    /** Removes all data. */
    public synchronized void clear() {
        store.clear();
        mtimes.clear();
        summaries.clear();
        lastMtime = 0;
    }
}
