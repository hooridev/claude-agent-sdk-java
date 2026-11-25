package dev.hoori.agents.claude.internal.session;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.internal.TranscriptMirror;
import dev.hoori.agents.claude.options.SessionStoreFlushMode;
import dev.hoori.agents.claude.session.SessionKey;
import dev.hoori.agents.claude.session.SessionStore;

/**
 * Accumulates {@code transcript_mirror} frames and flushes them to {@link SessionStore#append}
 * (Python: {@code transcript_mirror_batcher.py}).
 *
 * <p>Frames are buffered until {@link #flush()} (before each {@code result}) or until the buffer
 * exceeds {@value #MAX_PENDING_ENTRIES} entries / 1 MiB, which triggers a background flush. All
 * flushes run in submission order on one virtual thread, so appends per key keep the CLI's order.
 *
 * <p>Failed appends are retried ({@value #MAX_ATTEMPTS} attempts total, short backoff); a timeout
 * is not retried because the in-flight call may still land. Only then is the batch dropped and
 * reported to {@code onError}, which runs on the flush thread and must be quick. Nothing here
 * ever throws: the local transcript is already durable.
 */
public final class TranscriptMirrorBatcher implements TranscriptMirror {

    /** Entry threshold of the background flush (also the import batch size). */
    public static final int MAX_PENDING_ENTRIES = 500;
    /** Byte threshold of the background flush (approximate serialized size). */
    public static final int MAX_PENDING_BYTES = 1 << 20;
    /** Default timeout of one {@code append} call. */
    public static final Duration SEND_TIMEOUT = Duration.ofSeconds(60);
    static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {200, 800};

    private static final Logger LOG = System.getLogger(TranscriptMirrorBatcher.class.getName());

    private final SessionStore store;
    private final String projectsDir;
    private final BiConsumer<SessionKey, String> onError;
    private final Duration sendTimeout;
    private final int maxPendingEntries;
    private final int maxPendingBytes;
    private final ExecutorService flusher =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("transcript-mirror").factory());
    /** Serializes {@link #doFlush} when it runs inline after {@link #close()}. */
    private final ReentrantLock flushLock = new ReentrantLock();

    private List<Frame> pending = new ArrayList<>();
    private long pendingEntries;
    private long pendingBytes;

    private record Frame(String filePath, List<Map<String, Object>> entries) {
    }

    /**
     * @param projectsDir the subprocess's projects directory; frames outside it are dropped
     * @param onError     receives the key and message of a batch dropped after all attempts
     */
    public TranscriptMirrorBatcher(SessionStore store, String projectsDir, BiConsumer<SessionKey, String> onError,
                                   Duration sendTimeout, int maxPendingEntries, int maxPendingBytes) {
        this.store = store;
        this.projectsDir = projectsDir;
        this.onError = onError;
        this.sendTimeout = sendTimeout;
        this.maxPendingEntries = maxPendingEntries;
        this.maxPendingBytes = maxPendingBytes;
    }

    /** A batcher for a flush mode: {@code EAGER} zeroes the thresholds so every frame flushes. */
    public static TranscriptMirrorBatcher create(SessionStore store, String projectsDir,
                                                 BiConsumer<SessionKey, String> onError,
                                                 SessionStoreFlushMode flushMode) {
        boolean eager = flushMode == SessionStoreFlushMode.EAGER;
        return new TranscriptMirrorBatcher(store, projectsDir, onError, SEND_TIMEOUT,
                eager ? 0 : MAX_PENDING_ENTRIES, eager ? 0 : MAX_PENDING_BYTES);
    }

    int maxPendingEntries() {
        return maxPendingEntries;
    }

    int maxPendingBytes() {
        return maxPendingBytes;
    }

    /** Buffers a frame; schedules a background flush once a threshold is exceeded. */
    @Override
    public void enqueue(String filePath, List<Map<String, Object>> entries) {
        List<Map<String, Object>> safeEntries = entries == null ? List.of() : entries;
        long size = Json.write(safeEntries).length(); // one stringify per frame keeps this cheap
        boolean overflow;
        synchronized (this) {
            pending.add(new Frame(filePath, safeEntries));
            pendingEntries += safeEntries.size();
            pendingBytes += size;
            overflow = pendingEntries > maxPendingEntries || pendingBytes > maxPendingBytes;
        }
        if (overflow) {
            submitDrain();
        }
    }

    /** Flushes everything pending, after any in-flight background flush. Never throws. */
    @Override
    public void flush() {
        Future<?> drain = submitDrain();
        if (drain == null) {
            return;
        }
        try {
            drain.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(Level.ERROR, "[TranscriptMirrorBatcher] flush failed", e);
        }
    }

    /** Final flush, then stops the flush thread. Never throws. */
    @Override
    public void close() {
        flush();
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(sendTimeout.toMillis() * MAX_ATTEMPTS + 2_000, TimeUnit.MILLISECONDS)) {
                LOG.log(Level.DEBUG, "[TranscriptMirrorBatcher] flush thread still busy at close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Detaches the pending frames (so enqueue keeps filling a fresh buffer) and queues them for
     * the flush thread; after {@link #close()} the drain runs inline.
     *
     * @return the drain's future, or {@code null} if it already ran inline
     */
    private Future<?> submitDrain() {
        List<Frame> items;
        synchronized (this) {
            items = pending;
            pending = new ArrayList<>();
            pendingEntries = 0;
            pendingBytes = 0;
            // Submitting inside the lock keeps detach order == execution order.
            if (!flusher.isShutdown()) {
                try {
                    return flusher.submit(() -> drain(items));
                } catch (RejectedExecutionException ignored) {
                    // closed concurrently: run inline below
                }
            }
        }
        drain(items);
        return null;
    }

    private void drain(List<Frame> items) {
        if (items.isEmpty()) {
            return;
        }
        List<Map.Entry<SessionKey, String>> errors = new ArrayList<>();
        flushLock.lock();
        try {
            doFlush(items, errors);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "[TranscriptMirrorBatcher] flush raised", e);
        } finally {
            flushLock.unlock();
        }
        errors.forEach(error -> {
            try {
                onError.accept(error.getKey(), error.getValue());
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "[TranscriptMirrorBatcher] onError callback raised", e);
            }
        });
    }

    private void doFlush(List<Frame> items, List<Map.Entry<SessionKey, String>> errors) {
        // One append per file per flush; first-seen file order, entries keep enqueue order.
        Map<String, List<Map<String, Object>>> byPath = new LinkedHashMap<>();
        for (Frame item : items) {
            byPath.computeIfAbsent(item.filePath(), k -> new ArrayList<>()).addAll(item.entries());
        }
        for (Map.Entry<String, List<Map<String, Object>>> group : byPath.entrySet()) {
            List<Map<String, Object>> entries = group.getValue();
            if (entries.isEmpty()) {
                continue; // no phantom keys in adapters that touch storage on append([])
            }
            SessionKey key = group.getKey() == null ? null : SessionPaths.filePathToSessionKey(group.getKey(), projectsDir);
            if (key == null) {
                LOG.log(Level.WARNING, "[SessionStore] dropping mirror frame: filePath " + group.getKey()
                        + " is not under " + projectsDir
                        + " -- subprocess CLAUDE_CONFIG_DIR likely differs from parent (custom env / container?)");
                continue;
            }
            String error = appendWithRetry(key, entries);
            if (error != null) {
                LOG.log(Level.ERROR, "[TranscriptMirrorBatcher] flush failed for " + group.getKey() + ": " + error);
                errors.add(Map.entry(key, error));
            }
        }
    }

    /** @return {@code null} on success, else the last error's message */
    private String appendWithRetry(SessionKey key, List<Map<String, Object>> entries) {
        Exception last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (attempt > 0 && !sleep(BACKOFF_MS[attempt - 1])) {
                break;
            }
            try {
                TimedCalls.call(() -> {
                    store.append(key, entries);
                    return null;
                }, sendTimeout, "transcript-mirror-append");
                return null;
            } catch (TimeoutException e) {
                last = e;
                LOG.log(Level.DEBUG, "[TranscriptMirrorBatcher] append timed out after " + sendTimeout
                        + " -- not retrying");
                break; // the in-flight call may still land; a retry would duplicate it
            } catch (InterruptedException e) {
                last = e;
                break;
            } catch (Exception e) {
                last = e;
                LOG.log(Level.DEBUG, "[TranscriptMirrorBatcher] append attempt " + (attempt + 1) + "/"
                        + MAX_ATTEMPTS + " failed: " + e);
            }
        }
        if (last instanceof TimeoutException) {
            return "SessionStore.append() timed out after " + sendTimeout.toMillis() + "ms";
        }
        return last == null ? "interrupted" : String.valueOf(last.getMessage() != null ? last.getMessage() : last);
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
