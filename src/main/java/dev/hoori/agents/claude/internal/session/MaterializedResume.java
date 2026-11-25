package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A store-backed session written to a temporary directory laid out like {@code ~/.claude/}.
 *
 * @param configDir       the temporary {@code CLAUDE_CONFIG_DIR} for the subprocess
 * @param resumeSessionId the session id for {@code --resume} (for {@code continueConversation}
 *                        the most recent session of the store)
 */
public record MaterializedResume(Path configDir, String resumeSessionId) {

    private static final int DELETE_ATTEMPTS = 4;
    private static final long DELETE_RETRY_DELAY_MS = 100;
    /** Directories not cleaned up yet; removed at JVM exit so the copied access token never lingers. */
    private static final Set<Path> PENDING = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(
                () -> PENDING.forEach(FileOps::deleteTreeQuietly)));
    }

    /** Tracks a directory for deletion at JVM exit until {@link #deleteWithRetry} removed it. */
    static void track(Path dir) {
        PENDING.add(dir);
    }

    /** Removes {@link #configDir()}; call after the subprocess exited. Never throws. */
    public void cleanup() {
        deleteWithRetry(configDir);
    }

    /**
     * Deletes a tree, retrying transient failures (e.g. an indexer briefly holding the freshly
     * written {@code .credentials.json}) before a final best-effort sweep, so the access token does
     * not linger in temp.
     */
    static void deleteWithRetry(Path dir) {
        for (int attempt = 0; attempt < DELETE_ATTEMPTS && Files.exists(dir); attempt++) {
            try {
                FileOps.deleteTree(dir);
                break;
            } catch (IOException e) {
                try {
                    Thread.sleep(DELETE_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        FileOps.deleteTreeQuietly(dir);
        if (Files.notExists(dir)) {
            PENDING.remove(dir);
        }
    }
}
