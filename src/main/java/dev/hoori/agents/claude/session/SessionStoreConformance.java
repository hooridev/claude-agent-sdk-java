package dev.hoori.agents.claude.session;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import dev.hoori.agents.claude.internal.Json;

/**
 * Framework-free conformance suite for {@link SessionStore} adapters: asserts the 14 behavioral
 * contracts every adapter must satisfy and throws {@link AssertionError} on the first violation.
 *
 * <p>Contracts of optional methods ({@code listSessions}, {@code listSessionSummaries},
 * {@code delete}, {@code listSubkeys}) are skipped when named in {@code skipOptional} or when the
 * store's class does not override that method.
 *
 * <pre>{@code
 * // in a test method of your framework of choice
 * SessionStoreConformance.run(MyRedisStore::new);
 * }</pre>
 *
 * <p>Entries are compared by JSON value (key order and integer width are ignored).
 */
public final class SessionStoreConformance {

    /** Names of the optional {@link SessionStore} methods accepted in {@code skipOptional}. */
    public static final Set<String> OPTIONAL_METHODS =
            Set.of("listSessions", "listSessionSummaries", "delete", "listSubkeys");

    private static final SessionKey KEY = SessionKey.of("proj", "sess");

    private final Supplier<? extends SessionStore> makeStore;
    private final boolean hasListSessions;
    private final boolean hasListSummaries;
    private final boolean hasDelete;
    private final boolean hasListSubkeys;

    private SessionStoreConformance(Supplier<? extends SessionStore> makeStore, Set<String> skipOptional) {
        this.makeStore = makeStore;
        SessionStore probe = fresh();
        hasListSessions = hasOptional(probe, "listSessions", skipOptional);
        hasListSummaries = hasOptional(probe, "listSessionSummaries", skipOptional);
        hasDelete = hasOptional(probe, "delete", skipOptional);
        hasListSubkeys = hasOptional(probe, "listSubkeys", skipOptional);
    }

    /** Runs all contracts; {@code makeStore} is called once per contract for isolation. */
    public static void run(Supplier<? extends SessionStore> makeStore) throws Exception {
        run(makeStore, Set.of());
    }

    /**
     * Runs all contracts, skipping those of the optional methods named in {@code skipOptional}.
     *
     * @throws IllegalArgumentException if {@code skipOptional} names an unknown method
     * @throws AssertionError           on the first violated contract
     * @throws Exception                whatever the store throws
     */
    public static void run(Supplier<? extends SessionStore> makeStore, Set<String> skipOptional) throws Exception {
        Objects.requireNonNull(makeStore, "makeStore");
        Set<String> invalid = new HashSet<>(skipOptional);
        invalid.removeAll(OPTIONAL_METHODS);
        if (!invalid.isEmpty()) {
            throw new IllegalArgumentException("unknown optional methods in skipOptional: " + invalid);
        }
        SessionStoreConformance suite = new SessionStoreConformance(makeStore, skipOptional);
        suite.required();
        if (suite.hasListSessions) {
            suite.listSessions();
        }
        if (suite.hasListSummaries) {
            suite.listSessionSummaries();
        }
        if (suite.hasDelete) {
            suite.delete();
        }
        if (suite.hasListSubkeys) {
            suite.listSubkeys();
        }
    }

    private static boolean hasOptional(SessionStore store, String method, Set<String> skipOptional) {
        return !skipOptional.contains(method) && SessionStores.implementsMethod(store, method);
    }

    private SessionStore fresh() {
        return Objects.requireNonNull(makeStore.get(), "makeStore returned null");
    }

    // ---------------------------------------------------------------- required: append + load

    private void required() throws Exception {
        // 1. append then load returns the same entries in the same order
        SessionStore store = fresh();
        store.append(KEY, List.of(e("uuid", "b", "n", 1), e("uuid", "a", "n", 2)));
        check("1. append then load returns the same entries in order",
                sameJson(List.of(e("uuid", "b", "n", 1), e("uuid", "a", "n", 2)), store.load(KEY)));

        // 2. load of an unknown key returns null
        store = fresh();
        check("2. load of an unknown session returns null", store.load(SessionKey.of("proj", "nope")) == null);
        store.append(KEY, List.of(e("uuid", "x", "n", 1)));
        check("2. load of an unknown subpath returns null", store.load(sub("nope")) == null);

        // 3. multiple appends preserve call order
        store = fresh();
        store.append(KEY, List.of(e("uuid", "z", "n", 1)));
        store.append(KEY, List.of(e("uuid", "a", "n", 2), e("uuid", "m", "n", 3)));
        store.append(KEY, List.of(e("uuid", "b", "n", 4)));
        check("3. multiple appends preserve call order", sameJson(List.of(e("uuid", "z", "n", 1),
                e("uuid", "a", "n", 2), e("uuid", "m", "n", 3), e("uuid", "b", "n", 4)), store.load(KEY)));

        // 4. append of an empty list is a no-op
        store = fresh();
        store.append(KEY, List.of(e("uuid", "a", "n", 1)));
        store.append(KEY, List.of());
        check("4. append([]) is a no-op", sameJson(List.of(e("uuid", "a", "n", 1)), store.load(KEY)));

        // 5. subpath keys are stored independently of the main transcript
        store = fresh();
        store.append(KEY, List.of(e("uuid", "m", "n", 1)));
        store.append(sub("subagents/agent-1"), List.of(e("uuid", "s", "n", 1)));
        check("5. main transcript is independent of subpaths",
                sameJson(List.of(e("uuid", "m", "n", 1)), store.load(KEY)));
        check("5. subpath transcript is independent of main",
                sameJson(List.of(e("uuid", "s", "n", 1)), store.load(sub("subagents/agent-1"))));

        // 6. projectKey isolation
        store = fresh();
        store.append(SessionKey.of("A", "s1"), List.of(e("from", "A")));
        store.append(SessionKey.of("B", "s1"), List.of(e("from", "B")));
        check("6. projectKey A is isolated", sameJson(List.of(e("from", "A")), store.load(SessionKey.of("A", "s1"))));
        check("6. projectKey B is isolated", sameJson(List.of(e("from", "B")), store.load(SessionKey.of("B", "s1"))));
        if (hasListSessions) {
            check("6. listSessions is scoped to projectKey",
                    store.listSessions("A").size() == 1 && store.listSessions("B").size() == 1);
        }
    }

    // ---------------------------------------------------------------- optional: listSessions

    private void listSessions() throws Exception {
        // 7. listSessions returns the session ids of a project
        SessionStore store = fresh();
        store.append(SessionKey.of("proj", "a"), List.of(e("n", 1)));
        store.append(SessionKey.of("proj", "b"), List.of(e("n", 1)));
        store.append(SessionKey.of("other", "c"), List.of(e("n", 1)));
        List<SessionStoreListEntry> sessions = store.listSessions("proj");
        check("7. listSessions returns the project's session ids",
                sessionIds(sessions).stream().sorted().toList().equals(List.of("a", "b")));
        // > 1e12 rules out epoch seconds
        check("7. listSessions mtime is epoch milliseconds", sessions.stream().allMatch(s -> s.mtime() > 1e12));
        check("7. listSessions of an unknown project is empty", store.listSessions("never-appended-project").isEmpty());

        // 8. listSessions excludes subagent subpaths
        store = fresh();
        store.append(SessionKey.of("proj", "main"), List.of(e("n", 1)));
        store.append(new SessionKey("proj", "main", "subagents/agent-1"), List.of(e("n", 1)));
        check("8. listSessions excludes subpaths", sessionIds(store.listSessions("proj")).equals(List.of("main")));
    }

    // ---------------------------------------------------------------- optional: listSessionSummaries

    private void listSessionSummaries() throws Exception {
        // 14. summaries are the persisted fold output and round-trip through the fold again
        SessionStore store = fresh();
        SessionKey key = SessionKey.of("proj", "summ-sess");
        store.append(key, List.of(e("timestamp", "2024-01-01T00:00:00.000Z", "customTitle", "first"),
                e("timestamp", "2024-01-01T00:00:01.000Z")));
        store.append(key, List.of(e("timestamp", "2024-01-01T00:00:02.000Z", "customTitle", "second")));
        store.append(SessionKey.of("other", "elsewhere"), List.of(e("timestamp", "2024-01-01T00:00:00.000Z")));
        List<SessionSummaryEntry> summaries = store.listSessionSummaries("proj");
        check("14. listSessionSummaries returns the project's sessions",
                summaries.size() == 1 && "summ-sess".equals(summaries.getFirst().sessionId()));
        SessionSummaryEntry summary = summaries.getFirst();
        check("14. summary mtime is epoch milliseconds", summary.mtime() > 1e12);
        if (hasListSessions) {
            // Summary mtime is storage write time and must share listSessions()'s clock.
            long listed = store.listSessions("proj").stream().filter(s -> s.sessionId().equals("summ-sess"))
                    .mapToLong(SessionStoreListEntry::mtime).findFirst().orElse(Long.MAX_VALUE);
            check("14. summary mtime >= listSessions mtime", summary.mtime() >= listed);
        }
        check("14. summary data is an object", summary.data() != null);
        SessionSummaryEntry refolded = SessionSummaries.fold(summary, key,
                List.of(e("timestamp", "2024-01-01T00:00:03.000Z")));
        check("14. summary round-trips through the fold",
                "summ-sess".equals(refolded.sessionId()) && refolded.mtime() == summary.mtime());
        store.append(new SessionKey("proj", "summ-sess", "subagents/agent-1"),
                List.of(e("timestamp", "2024-01-01T00:00:09.000Z", "customTitle", "subagent")));
        SessionSummaryEntry afterSub = store.listSessionSummaries("proj").stream()
                .filter(s -> s.sessionId().equals("summ-sess")).findFirst().orElse(null);
        check("14. subagent appends do not affect the main summary",
                afterSub != null && sameJson(summary.data(), afterSub.data()));
        check("14. listSessionSummaries of an unknown project is empty",
                store.listSessionSummaries("never-appended-project").isEmpty());
        if (hasDelete) {
            store.delete(key);
            check("14. delete drops the summary", store.listSessionSummaries("proj").isEmpty());
        }
    }

    // ---------------------------------------------------------------- optional: delete

    private void delete() throws Exception {
        // 9. delete main, then load returns null
        SessionStore store = fresh();
        store.delete(SessionKey.of("proj", "never-written"));
        store.append(KEY, List.of(e("n", 1)));
        store.delete(KEY);
        check("9. load after delete returns null", store.load(KEY) == null);

        // 10. deleting main cascades to its subkeys
        store = fresh();
        SessionKey sub1 = sub("subagents/agent-1");
        SessionKey sub2 = sub("subagents/agent-2");
        SessionKey other = SessionKey.of("proj", "sess2");
        SessionKey otherProject = SessionKey.of("other-proj", KEY.sessionId());
        for (SessionKey k : List.of(KEY, sub1, sub2, other, otherProject)) {
            store.append(k, List.of(e("n", 1)));
        }
        store.delete(KEY);
        check("10. delete removes main", store.load(KEY) == null);
        check("10. delete cascades to subkeys", store.load(sub1) == null && store.load(sub2) == null);
        check("10. delete keeps other sessions", sizeOf(store.load(other)) == 1);
        check("10. delete keeps other projects", sizeOf(store.load(otherProject)) == 1);
        if (hasListSubkeys) {
            check("10. no subkeys after cascade", store.listSubkeys(subkeysKey()).isEmpty());
        }
        if (hasListSessions) {
            check("10. deleted session is not listed",
                    !sessionIds(store.listSessions(KEY.projectKey())).contains(KEY.sessionId()));
        }

        // 11. delete with subpath removes only that subkey
        store = fresh();
        store.append(KEY, List.of(e("n", 1)));
        store.append(sub1, List.of(e("n", 1)));
        store.append(sub2, List.of(e("n", 1)));
        store.delete(sub1);
        check("11. subpath delete removes the subkey", store.load(sub1) == null);
        check("11. subpath delete keeps sibling subkeys", sizeOf(store.load(sub2)) == 1);
        check("11. subpath delete keeps main", sizeOf(store.load(KEY)) == 1);
        if (hasListSubkeys) {
            check("11. remaining subkeys", store.listSubkeys(subkeysKey()).equals(List.of("subagents/agent-2")));
        }
    }

    // ---------------------------------------------------------------- optional: listSubkeys

    private void listSubkeys() throws Exception {
        // 12. listSubkeys returns the subpaths of a session
        SessionStore store = fresh();
        store.append(KEY, List.of(e("n", 1)));
        store.append(sub("subagents/agent-1"), List.of(e("n", 1)));
        store.append(sub("subagents/agent-2"), List.of(e("n", 1)));
        store.append(new SessionKey(KEY.projectKey(), "other-sess", "subagents/agent-x"), List.of(e("n", 1)));
        List<String> subkeys = store.listSubkeys(subkeysKey());
        check("12. listSubkeys returns the session's subpaths",
                subkeys.stream().sorted().toList().equals(List.of("subagents/agent-1", "subagents/agent-2")));

        // 13. listSubkeys excludes the main transcript
        store = fresh();
        store.append(KEY, List.of(e("n", 1)));
        check("13. listSubkeys excludes the main transcript", store.listSubkeys(subkeysKey()).isEmpty());
        check("13. listSubkeys of an unknown session is empty",
                store.listSubkeys(new SessionListSubkeysKey("proj", "never-appended")).isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    /** A test entry; {@code type} is required by the entry contract, its value is irrelevant. */
    private static Map<String, Object> e(Object... keyValues) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "x");
        entry.putAll(Json.obj(keyValues));
        return entry;
    }

    private static SessionKey sub(String subpath) {
        return new SessionKey(KEY.projectKey(), KEY.sessionId(), subpath);
    }

    private static SessionListSubkeysKey subkeysKey() {
        return new SessionListSubkeysKey(KEY.projectKey(), KEY.sessionId());
    }

    private static List<String> sessionIds(List<SessionStoreListEntry> entries) {
        List<String> ids = new ArrayList<>();
        entries.forEach(s -> ids.add(s.sessionId()));
        return ids;
    }

    private static int sizeOf(List<?> entries) {
        return entries == null ? -1 : entries.size();
    }

    /** Deep equality by JSON value: ignores key order and integer width. */
    private static boolean sameJson(Object expected, Object actual) {
        return actual != null && Json.parse(Json.write(expected)).equals(Json.parse(Json.write(actual)));
    }

    private static void check(String contract, boolean condition) {
        if (!condition) {
            throw new AssertionError("SessionStore contract violated: " + contract);
        }
    }
}
