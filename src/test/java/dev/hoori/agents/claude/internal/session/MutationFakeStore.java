package dev.hoori.agents.claude.internal.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionListSubkeysKey;
import dev.hoori.agents.claude.session.SessionStore;
import dev.hoori.agents.claude.session.SessionStoreListEntry;

/** In-test {@link SessionStore} implementing every optional method and recording append calls. */
public class MutationFakeStore implements SessionStore {

    /** One recorded {@code append} call. */
    public record Call(SessionKey key, List<Map<String, Object>> entries) {
    }

    public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    public final Map<SessionKey, List<Map<String, Object>>> data = Collections.synchronizedMap(new LinkedHashMap<>());
    public final Map<String, Long> mtimes = Collections.synchronizedMap(new LinkedHashMap<>());
    public final List<SessionKey> deleted = Collections.synchronizedList(new ArrayList<>());
    private long clock = 1;

    @Override
    public void append(SessionKey key, List<Map<String, Object>> entries) throws Exception {
        List<Map<String, Object>> copy = new ArrayList<>();
        entries.forEach(e -> copy.add(Json.deepCopy(e)));
        calls.add(new Call(key, copy));
        data.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>())).addAll(copy);
        if (key.subpath() == null) {
            mtimes.put(key.projectKey() + "/" + key.sessionId(), clock++);
        }
    }

    @Override
    public List<Map<String, Object>> load(SessionKey key) throws Exception {
        List<Map<String, Object>> entries = data.get(key);
        return entries == null ? null : new ArrayList<>(entries);
    }

    @Override
    public List<SessionStoreListEntry> listSessions(String projectKey) throws Exception {
        List<SessionStoreListEntry> out = new ArrayList<>();
        mtimes.forEach((k, v) -> {
            if (k.startsWith(projectKey + "/")) {
                out.add(new SessionStoreListEntry(k.substring(projectKey.length() + 1), v));
            }
        });
        return out;
    }

    @Override
    public void delete(SessionKey key) throws Exception {
        deleted.add(key);
        data.keySet().removeIf(k -> k.projectKey().equals(key.projectKey()) && k.sessionId().equals(key.sessionId())
                && (key.subpath() == null || key.subpath().equals(k.subpath())));
    }

    @Override
    public List<String> listSubkeys(SessionListSubkeysKey key) throws Exception {
        List<String> out = new ArrayList<>();
        synchronized (data) {
            data.keySet().stream()
                    .filter(k -> k.projectKey().equals(key.projectKey()) && k.sessionId().equals(key.sessionId())
                            && k.subpath() != null)
                    .forEach(k -> out.add(k.subpath()));
        }
        return out;
    }

    /** All entries appended for {@code key}, flattened. */
    public List<Map<String, Object>> entries(SessionKey key) {
        List<Map<String, Object>> entries = data.get(key);
        return entries == null ? List.of() : new ArrayList<>(entries);
    }
}
