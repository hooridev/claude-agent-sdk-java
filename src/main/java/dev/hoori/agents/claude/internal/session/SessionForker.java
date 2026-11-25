package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionStore;

/** Forks a session on disk or inside a {@link SessionStore} via {@link ForkTranscriptBuilder}. */
public final class SessionForker {

    private SessionForker() {
    }

    /**
     * Forks a disk session into a new {@code <newId>.jsonl} next to the source (mode 0600).
     *
     * @return the new session id
     */
    public static String forkOnDisk(String sessionId, String directory, String upToMessageId, String title)
            throws IOException {
        validate(sessionId, upToMessageId);
        Path source = SessionFileMutator.requireSessionFile(sessionId, directory);
        byte[] content = Files.readAllBytes(source);
        if (content.length == 0) {
            throw new IllegalArgumentException("Session " + sessionId + " has no messages to fork");
        }
        ForkTranscriptBuilder.Fork fork = ForkTranscriptBuilder.build(
                ForkTranscriptBuilder.parseJsonl(content, sessionId), sessionId, upToMessageId, title,
                () -> ForkTranscriptBuilder.deriveTitleFromBytes(content));
        StringBuilder jsonl = new StringBuilder();
        fork.entries().forEach(entry -> jsonl.append(Json.write(entry)).append('\n'));
        FileOps.writeNewPrivate(source.resolveSibling(fork.sessionId() + ".jsonl"),
                jsonl.toString().getBytes(StandardCharsets.UTF_8));
        return fork.sessionId();
    }

    /**
     * Forks a store session (under {@code directory}'s project key) into a new key of the same store.
     *
     * @return the new session id
     * @throws NoSuchFileException if the store has no entries for the source
     */
    public static String forkInStore(SessionStore store, String sessionId, String directory, String upToMessageId,
                                     String title) throws Exception {
        validate(sessionId, upToMessageId);
        String projectKey = SessionPaths.projectKeyForDirectory(directory);
        List<Map<String, Object>> loaded = store.load(SessionKey.of(projectKey, sessionId));
        if (loaded == null || loaded.isEmpty()) {
            throw new NoSuchFileException("Session " + sessionId + " not found");
        }
        ForkTranscriptBuilder.Fork fork = ForkTranscriptBuilder.build(
                ForkTranscriptBuilder.partition(loaded, sessionId), sessionId, upToMessageId, title,
                () -> ForkTranscriptBuilder.deriveTitleFromEntries(loaded));
        store.append(SessionKey.of(projectKey, fork.sessionId()), fork.entries());
        return fork.sessionId();
    }

    private static void validate(String sessionId, String upToMessageId) {
        MetadataEntries.requireUuid(sessionId, "session_id");
        if (upToMessageId != null && !upToMessageId.isEmpty()) {
            MetadataEntries.requireUuid(upToMessageId, "up_to_message_id");
        }
    }
}
