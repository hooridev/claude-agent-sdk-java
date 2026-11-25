package dev.hoori.agents.claude.internal.session;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.hoori.agents.claude.exception.ClaudeSdkException;
import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.session.SessionText.LiteSessionFile;
import dev.hoori.agents.claude.session.SessionInfo;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionListSubkeysKey;
import dev.hoori.agents.claude.session.SessionMessage;
import dev.hoori.agents.claude.session.SessionStore;
import dev.hoori.agents.claude.session.SessionStoreListEntry;
import dev.hoori.agents.claude.session.SessionStores;
import dev.hoori.agents.claude.session.SessionSummaries;
import dev.hoori.agents.claude.session.SessionSummaryEntry;

/**
 * Reads sessions from a {@link SessionStore} (Python: the {@code *_from_store} functions of
 * {@code sessions.py}). Session metadata is derived with the same lite parse as the disk path, so
 * both produce identical results for the same transcript.
 *
 * <p>Checked adapter exceptions are wrapped in {@link ClaudeSdkException}; runtime exceptions
 * propagate unchanged.
 */
public final class StoreSessionReader {

    private static final System.Logger LOG = System.getLogger(StoreSessionReader.class.getName());

    /** Upper bound on concurrent {@code store.load()} calls while listing. */
    static final int LOAD_CONCURRENCY = 16;

    private StoreSessionReader() {
    }

    // ---------------------------------------------------------------- listing

    /**
     * Lists the sessions of {@code directory}'s project key. Uses {@code listSessionSummaries}
     * (gap-filled via {@code listSessions} + {@code load}) when implemented, else one {@code load}
     * per session.
     *
     * @throws IllegalArgumentException if the store implements neither listing method
     */
    public static List<SessionInfo> listSessions(SessionStore store, String directory, int limit, int offset) {
        String projectPath = SessionPaths.canonicalizePath(directory != null ? directory : ".");
        String projectKey = SessionPaths.sanitizePath(projectPath);
        boolean hasListSessions = SessionStores.implementsMethod(store, "listSessions");
        if (SessionStores.implementsMethod(store, "listSessionSummaries")) {
            List<SessionSummaryEntry> summaries;
            try {
                summaries = call(() -> store.listSessionSummaries(projectKey));
            } catch (UnsupportedOperationException e) {
                summaries = null; // declared but unsupported: fall back to the load path
            }
            if (summaries != null) {
                return listFromSummaries(store, projectKey, projectPath, summaries, hasListSessions, limit, offset);
            }
        }
        if (!hasListSessions) {
            throw new IllegalArgumentException("sessionStore implements neither listSessionSummaries() nor "
                    + "listSessions() -- cannot list sessions. Provide a store with at least one of those methods.");
        }
        // Filter (sidechain / empty) before paging, like the disk path.
        List<SessionStoreListEntry> listing = new ArrayList<>(call(() -> store.listSessions(projectKey)));
        return LocalSessionReader.sortAndPage(deriveInfosViaLoad(store, listing, projectKey, projectPath),
                limit, offset);
    }

    /** One page slot: a summary-derived info, or a placeholder ({@code info == null}) to gap-fill via load. */
    private record Slot(String sessionId, long mtime, SessionInfo info) {
    }

    private static List<SessionInfo> listFromSummaries(SessionStore store, String projectKey, String projectPath,
                                                       List<SessionSummaryEntry> summaries, boolean hasListSessions,
                                                       int limit, int offset) {
        List<SessionStoreListEntry> listing = List.of();
        Map<String, Long> knownMtimes = new HashMap<>();
        if (hasListSessions) {
            listing = new ArrayList<>(call(() -> store.listSessions(projectKey)));
            listing.forEach(e -> knownMtimes.put(e.sessionId(), e.mtime()));
        } else {
            LOG.log(Level.DEBUG, "listSessionSummaries without listSessions: gap-fill skipped; "
                    + "sessions lacking a summary will be omitted");
        }

        List<Slot> slots = new ArrayList<>();
        Set<String> freshSummaryIds = new HashSet<>();
        for (SessionSummaryEntry summary : summaries) {
            String sid = summary.sessionId();
            if (hasListSessions) {
                Long known = knownMtimes.get(sid);
                if (known == null || summary.mtime() < known) {
                    continue; // session gone, or stale summary: re-derive from source via gap-fill
                }
            }
            freshSummaryIds.add(sid);
            // Sidechain / empty summaries are dropped here so they don't consume page slots.
            SessionInfo info = SessionSummaries.toSessionInfo(summary, projectPath);
            if (info != null) {
                slots.add(new Slot(sid, summary.mtime(), info));
            }
        }
        for (SessionStoreListEntry entry : listing) {
            if (!freshSummaryIds.contains(entry.sessionId())) {
                slots.add(new Slot(entry.sessionId(), entry.mtime(), null));
            }
        }

        // Page BEFORE loading, so gap-fill loads are bounded by the page size.
        slots.sort(Comparator.comparingLong(Slot::mtime).reversed());
        List<Slot> page = Paging.page(slots, limit, offset);
        List<SessionStoreListEntry> toFill = page.stream().filter(s -> s.info() == null)
                .map(s -> new SessionStoreListEntry(s.sessionId(), s.mtime())).toList();
        Map<String, SessionInfo> filled = new HashMap<>();
        deriveInfosViaLoad(store, toFill, projectKey, projectPath).forEach(i -> filled.put(i.sessionId(), i));
        // Placeholders that resolve to nothing (sidechain / no summary) are dropped after paging.
        List<SessionInfo> result = new ArrayList<>();
        for (Slot slot : page) {
            SessionInfo info = slot.info() != null ? slot.info() : filled.get(slot.sessionId());
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Loads each listed session (at most {@link #LOAD_CONCURRENCY} at a time) and lite-parses it.
     * A failing load degrades that row to an empty summary; sidechain and no-summary sessions are dropped.
     */
    private static List<SessionInfo> deriveInfosViaLoad(SessionStore store, List<SessionStoreListEntry> listing,
                                                        String projectKey, String projectPath) {
        if (listing.isEmpty()) {
            return List.of();
        }
        List<Callable<String>> loads = listing.stream()
                .<Callable<String>>map(e -> () -> loadAsJsonl(store, SessionKey.of(projectKey, e.sessionId())))
                .toList();
        List<Future<String>> outcomes;
        try (ExecutorService executor = Executors.newFixedThreadPool(LOAD_CONCURRENCY,
                Thread.ofVirtual().name("session-store-load-", 0).factory())) {
            outcomes = executor.invokeAll(loads);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeSdkException("Interrupted while loading sessions from the store", e);
        }

        List<SessionInfo> results = new ArrayList<>();
        for (int i = 0; i < listing.size(); i++) {
            SessionStoreListEntry entry = listing.get(i);
            String jsonl;
            try {
                jsonl = outcomes.get(i).get();
            } catch (ExecutionException | InterruptedException e) {
                LOG.log(Level.DEBUG, "store.load() failed for session " + entry.sessionId(), e.getCause());
                results.add(new SessionInfo(entry.sessionId(), "", entry.mtime(), null, null, null, null, null,
                        null, null));
                continue;
            }
            SessionInfo parsed = jsonl == null ? null
                    : LiteSessionInfoParser.parse(entry.sessionId(), jsonlToLite(jsonl, entry.mtime()), projectPath);
            if (parsed != null) {
                results.add(withLastModified(parsed, entry.mtime()));
            }
        }
        return results;
    }

    // ---------------------------------------------------------------- single session

    /** Metadata of one stored session, or {@code null} if not found, a sidechain, or without summary. */
    public static SessionInfo getSessionInfo(SessionStore store, String sessionId, String directory) {
        if (SessionPaths.validateUuid(sessionId) == null) {
            return null;
        }
        String jsonl = loadAsJsonl(store, SessionKey.of(SessionPaths.projectKeyForDirectory(directory), sessionId));
        if (jsonl == null) {
            return null;
        }
        String projectPath = SessionPaths.canonicalizePath(directory != null ? directory : ".");
        return LiteSessionInfoParser.parse(sessionId, jsonlToLite(jsonl, mtimeFromJsonlTail(jsonl)), projectPath);
    }

    /** The conversation of a stored session; store entries feed the chain builder directly. */
    public static List<SessionMessage> getSessionMessages(SessionStore store, String sessionId, String directory,
                                                          int limit, int offset) {
        if (SessionPaths.validateUuid(sessionId) == null) {
            return List.of();
        }
        SessionKey key = SessionKey.of(SessionPaths.projectKeyForDirectory(directory), sessionId);
        List<Map<String, Object>> entries = call(() -> store.load(key));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return TranscriptChain.toSessionMessages(TranscriptChain.filterEntries(entries), limit, offset);
    }

    // ---------------------------------------------------------------- subagents

    /**
     * Subagent ids of a stored session, deduplicated across subpaths.
     *
     * @throws IllegalArgumentException if the store does not implement {@code listSubkeys}
     */
    public static List<String> listSubagents(SessionStore store, String sessionId, String directory) {
        if (SessionPaths.validateUuid(sessionId) == null) {
            return List.of();
        }
        if (!SessionStores.implementsMethod(store, "listSubkeys")) {
            throw new IllegalArgumentException("sessionStore does not implement listSubkeys() -- cannot list "
                    + "subagents. Provide a store with a listSubkeys() method.");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String subpath : subkeys(store, SessionPaths.projectKeyForDirectory(directory), sessionId)) {
            String last = lastSegment(subpath);
            if (subpath.startsWith("subagents/") && last.startsWith("agent-")) {
                ids.add(last.substring("agent-".length()));
            }
        }
        return List.copyOf(ids);
    }

    /**
     * A stored subagent's conversation. Scans subkeys when the store implements
     * {@code listSubkeys} (subagents may be nested), else tries {@code subagents/agent-<id>}.
     * Parent ids come from the last {@code agent_metadata} entry.
     */
    public static List<SessionMessage> getSubagentMessages(SessionStore store, String sessionId, String agentId,
                                                           String directory, int limit, int offset) {
        if (SessionPaths.validateUuid(sessionId) == null || agentId == null || agentId.isEmpty()) {
            return List.of();
        }
        String projectKey = SessionPaths.projectKeyForDirectory(directory);
        String target = "agent-" + agentId;
        String subpath = "subagents/" + target;
        if (SessionStores.implementsMethod(store, "listSubkeys")) {
            subpath = subkeys(store, projectKey, sessionId).stream()
                    .filter(sk -> sk.startsWith("subagents/") && lastSegment(sk).equals(target))
                    .findFirst().orElse(null);
            if (subpath == null) {
                return List.of();
            }
        }
        SessionKey key = new SessionKey(projectKey, sessionId, subpath);
        List<Map<String, Object>> entries = call(() -> store.load(key));
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        SessionPaths.SplitMetadata split = SessionPaths.splitAgentMetadata(entries);
        if (split.transcript().isEmpty()) {
            return List.of();
        }
        String[] parentIds = SessionPaths.parentIdsFromAgentMetadata(split.metadata());
        return TranscriptChain.toSubagentMessages(TranscriptChain.filterEntries(split.transcript()), limit, offset,
                parentIds[0], parentIds[1]);
    }

    private static List<String> subkeys(SessionStore store, String projectKey, String sessionId) {
        return call(() -> store.listSubkeys(new SessionListSubkeysKey(projectKey, sessionId)));
    }

    private static String lastSegment(String subpath) {
        return subpath.substring(subpath.lastIndexOf('/') + 1);
    }

    // ---------------------------------------------------------------- JSONL view of store entries

    /** Loads a session and serializes it to JSONL; {@code null} if it has no entries. */
    private static String loadAsJsonl(SessionStore store, SessionKey key) {
        List<Map<String, Object>> entries = call(() -> store.load(key));
        return entries == null || entries.isEmpty() ? null : toJsonl(entries);
    }

    /**
     * One compact JSON object per line. {@code type} is hoisted to the front because adapters may
     * reorder keys (e.g. JSONB) while the tag scan matches {@code {"type":"tag",...}} as a line prefix.
     */
    static String toJsonl(List<Map<String, Object>> entries) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> entry : entries) {
            Object line = entry;
            if (entry != null && entry.containsKey("type")) {
                Map<String, Object> typeFirst = new LinkedHashMap<>();
                typeFirst.put("type", entry.get("type"));
                typeFirst.putAll(entry);
                line = typeFirst;
            }
            sb.append(Json.write(line)).append('\n');
        }
        return sb.toString();
    }

    /** The head/tail view of an in-memory JSONL, with the byte semantics of {@link SessionText#readSessionLite}. */
    static LiteSessionFile jsonlToLite(String jsonl, long mtime) {
        byte[] bytes = jsonl.getBytes(StandardCharsets.UTF_8);
        int size = bytes.length;
        int buf = SessionText.LITE_READ_BUF_SIZE;
        // new String(...) replaces malformed input, like the decoder of the disk path
        String head = new String(bytes, 0, Math.min(size, buf), StandardCharsets.UTF_8);
        String tail = size > buf ? new String(bytes, size - buf, buf, StandardCharsets.UTF_8) : head;
        return new LiteSessionFile(mtime, size, head, tail);
    }

    /** Best-effort mtime from the last entry's {@code timestamp}; falls back to now. */
    static long mtimeFromJsonlTail(String jsonl) {
        String trimmed = jsonl.stripTrailing();
        String lastLine = trimmed.substring(trimmed.lastIndexOf('\n') + 1);
        try {
            Long ms = SessionSummaries.isoToEpochMillis(Json.parseObject(lastLine).get("timestamp"));
            if (ms != null) {
                return ms;
            }
        } catch (RuntimeException e) {
            // not a JSON object
        }
        return System.currentTimeMillis();
    }

    private static SessionInfo withLastModified(SessionInfo i, long mtime) {
        return new SessionInfo(i.sessionId(), i.summary(), mtime, i.fileSize(), i.customTitle(), i.firstPrompt(),
                i.gitBranch(), i.cwd(), i.tag(), i.createdAt());
    }

    /** Runs an adapter call, wrapping checked exceptions. */
    private static <T> T call(Callable<T> adapterCall) {
        try {
            return adapterCall.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ClaudeSdkException("SessionStore call failed: " + e.getMessage(), e);
        }
    }
}
