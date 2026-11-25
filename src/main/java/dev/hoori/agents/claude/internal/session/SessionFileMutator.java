package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * Mutations of on-disk session transcripts: appending metadata entries and hard deletion
 * (Python: the local half of {@code session_mutations.py}).
 *
 * <p>If the session is open in a CLI process, the CLI tail-reads before re-appending its cached
 * metadata, so an SDK-appended entry in the tail window wins over the CLI's stale value.
 */
public final class SessionFileMutator {

    private SessionFileMutator() {
    }

    /**
     * Locates a non-empty session file ({@code directory}'s project dir and worktrees, or every
     * project dir when {@code directory} is {@code null}).
     *
     * @throws NoSuchFileException if there is none
     */
    public static Path requireSessionFile(String sessionId, String directory) throws NoSuchFileException {
        Path path = SessionPaths.resolveSessionFilePath(sessionId, directory);
        if (path == null) {
            throw notFound(sessionId, directory);
        }
        return path;
    }

    /** Appends {@code entry} as one compact JSONL line to the session's file. */
    public static void append(String sessionId, Map<String, Object> entry, String directory) throws IOException {
        Path path = requireSessionFile(sessionId, directory);
        byte[] line = (Json.write(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        // No CREATE: a file deleted since it was resolved fails instead of being recreated.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            if (channel.size() == 0) {
                throw notFound(sessionId, directory); // truncated since it was resolved
            }
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (NoSuchFileException e) {
            throw notFound(sessionId, directory);
        }
    }

    /** Deletes the session file and its sibling {@code <sessionId>/} subagent directory (if any). */
    public static void delete(String sessionId, String directory) throws IOException {
        Path path = requireSessionFile(sessionId, directory);
        try {
            java.nio.file.Files.delete(path);
        } catch (NoSuchFileException e) {
            throw notFound(sessionId, null);
        }
        FileOps.deleteTreeQuietly(path.resolveSibling(sessionId));
    }

    static NoSuchFileException notFound(String sessionId, String directory) {
        return new NoSuchFileException("Session " + sessionId + " not found"
                + (directory != null && !directory.isEmpty() ? " in project directory for " + directory : ""));
    }
}
