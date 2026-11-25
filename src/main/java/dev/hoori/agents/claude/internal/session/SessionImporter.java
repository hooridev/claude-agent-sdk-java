package dev.hoori.agents.claude.internal.session;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionStore;

/**
 * Replays an on-disk session (and its subagent transcripts plus {@code .meta.json} sidecars)
 * into a {@link SessionStore} (Python: {@code session_import.py}).
 *
 * <p>Keys use the on-disk project directory name, so an imported session is indistinguishable
 * from a live-mirrored one.
 */
public final class SessionImporter {

    private SessionImporter() {
    }

    /**
     * Imports a session.
     *
     * @param batchSize maximum entries per {@code append}; a non-positive value uses the default (500)
     * @throws java.nio.file.NoSuchFileException if the session file cannot be found
     */
    public static void importSession(String sessionId, SessionStore store, String directory, boolean includeSubagents,
                                     int batchSize) throws Exception {
        MetadataEntries.requireUuid(sessionId, "session_id");
        Path resolved = SessionPaths.resolveSessionFilePath(sessionId, directory);
        if (resolved == null) {
            throw new java.nio.file.NoSuchFileException("Session " + sessionId + " not found");
        }
        String projectKey = resolved.getParent().getFileName().toString();
        int limit = batchSize > 0 ? batchSize : TranscriptMirrorBatcher.MAX_PENDING_ENTRIES;

        appendInBatches(resolved, SessionKey.of(projectKey, sessionId), store, limit);
        if (!includeSubagents) {
            return;
        }
        Path sessionDir = resolved.resolveSibling(sessionId);
        List<Path> files = new ArrayList<>();
        collectJsonl(sessionDir.resolve("subagents"), files);
        for (Path file : files) {
            List<String> parts = new ArrayList<>();
            sessionDir.relativize(file).forEach(part -> parts.add(part.toString()));
            String last = parts.removeLast();
            parts.add(last.substring(0, last.length() - ".jsonl".length()));
            SessionKey subKey = new SessionKey(projectKey, sessionId, String.join("/", parts));
            appendInBatches(file, subKey, store, limit);

            // agent_metadata only exists in the sidecar on disk; import it so resume can recreate it.
            Map<String, Object> meta = SessionPaths.readAgentMetadataSidecar(file);
            if (meta != null) {
                Map<String, Object> metaEntry = new LinkedHashMap<>(meta);
                metaEntry.put("type", "agent_metadata"); // a stray "type" in the sidecar never wins
                store.append(subKey, List.of(metaEntry));
            }
        }
    }

    private static void appendInBatches(Path file, SessionKey key, SessionStore store, int batchSize)
            throws Exception {
        List<Map<String, Object>> batch = new ArrayList<>();
        long chars = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                batch.add(Json.parseObject(line));
                chars += line.length();
                if (batch.size() >= batchSize || chars >= TranscriptMirrorBatcher.MAX_PENDING_BYTES) {
                    store.append(key, batch);
                    batch = new ArrayList<>();
                    chars = 0;
                }
            }
        }
        if (!batch.isEmpty()) {
            store.append(key, batch);
        }
    }

    /** All {@code *.jsonl} files under {@code dir}, depth-first, sorted by name per directory. */
    private static void collectJsonl(Path dir, List<Path> out) {
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            entries.forEach(children::add);
        } catch (IOException e) {
            return; // absent or unreadable
        }
        children.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path child : children) {
            if (Files.isDirectory(child)) {
                collectJsonl(child, out);
            } else if (Files.isRegularFile(child) && child.getFileName().toString().endsWith(".jsonl")) {
                out.add(child);
            }
        }
    }
}
