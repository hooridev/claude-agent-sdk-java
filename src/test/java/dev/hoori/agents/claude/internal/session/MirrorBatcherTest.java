package dev.hoori.agents.claude.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.hoori.agents.claude.internal.Json;
import dev.hoori.agents.claude.options.SessionStoreFlushMode;
import dev.hoori.agents.claude.session.SessionKey;

class MirrorBatcherTest {

    private static final String PROJECTS = "/cfg/projects";
    private static final String MAIN = PROJECTS + "/proj/sess.jsonl";
    private static final String SUB = PROJECTS + "/proj/sess/subagents/agent-1.jsonl";
    private static final SessionKey MAIN_KEY = SessionKey.of("proj", "sess");

    private final List<Map.Entry<SessionKey, String>> errors = Collections.synchronizedList(new ArrayList<>());

    private TranscriptMirrorBatcher batcher(MutationFakeStore store, int maxEntries, int maxBytes, Duration timeout) {
        return new TranscriptMirrorBatcher(store, PROJECTS, (k, e) -> errors.add(Map.entry(k, e)), timeout,
                maxEntries, maxBytes);
    }

    private TranscriptMirrorBatcher batcher(MutationFakeStore store) {
        return batcher(store, TranscriptMirrorBatcher.MAX_PENDING_ENTRIES, TranscriptMirrorBatcher.MAX_PENDING_BYTES,
                Duration.ofSeconds(5));
    }

    private static List<Map<String, Object>> entries(String... uuids) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String uuid : uuids) {
            out.add(Json.obj("type", "user", "uuid", uuid));
        }
        return out;
    }

    private static List<Object> uuids(List<Map<String, Object>> entries) {
        return entries.stream().map(e -> e.get("uuid")).toList();
    }

    @Test
    void enqueueThenFlushAppendsCoalescedPerFileInOrder() {
        MutationFakeStore store = new MutationFakeStore();
        TranscriptMirrorBatcher b = batcher(store);
        b.enqueue(MAIN, entries("a"));
        b.enqueue(SUB, entries("s1"));
        b.enqueue(MAIN, entries("b", "c"));
        b.enqueue(MAIN, List.of());
        assertTrue(store.calls.isEmpty(), "nothing is sent before flush");
        b.flush();
        assertEquals(2, store.calls.size());
        assertEquals(MAIN_KEY, store.calls.get(0).key());
        assertEquals(List.of("a", "b", "c"), uuids(store.calls.get(0).entries()));
        assertEquals(new SessionKey("proj", "sess", "subagents/agent-1"), store.calls.get(1).key());
        b.close();
    }

    @Test
    void emptyEntriesAndUnmappedPathsAreNotAppended() {
        MutationFakeStore store = new MutationFakeStore();
        TranscriptMirrorBatcher b = batcher(store);
        b.enqueue(MAIN, List.of());
        b.enqueue("/elsewhere/proj/sess.jsonl", entries("x"));
        b.enqueue(null, entries("y"));
        b.flush();
        assertTrue(store.calls.isEmpty());
        assertTrue(errors.isEmpty());
        b.close();
    }

    @Test
    void eagerFlushOnEntryAndByteThresholds() throws Exception {
        MutationFakeStore store = new MutationFakeStore();
        TranscriptMirrorBatcher byCount = batcher(store, 2, Integer.MAX_VALUE, Duration.ofSeconds(5));
        byCount.enqueue(MAIN, entries("a", "b"));
        Thread.sleep(100);
        assertTrue(store.calls.isEmpty(), "at the threshold, not above it");
        byCount.enqueue(MAIN, entries("c"));
        awaitCalls(store, 1);
        assertEquals(List.of("a", "b", "c"), uuids(store.calls.getFirst().entries()));
        byCount.close();

        MutationFakeStore store2 = new MutationFakeStore();
        TranscriptMirrorBatcher byBytes = batcher(store2, Integer.MAX_VALUE, 10, Duration.ofSeconds(5));
        byBytes.enqueue(MAIN, entries("a-long-enough-uuid"));
        awaitCalls(store2, 1);
        byBytes.close();
    }

    @Test
    void flushModeSetsThresholds() {
        MutationFakeStore store = new MutationFakeStore();
        TranscriptMirrorBatcher batched = TranscriptMirrorBatcher.create(store, PROJECTS, (k, e) -> { },
                SessionStoreFlushMode.BATCHED);
        assertEquals(500, batched.maxPendingEntries());
        assertEquals(1 << 20, batched.maxPendingBytes());
        TranscriptMirrorBatcher eager = TranscriptMirrorBatcher.create(store, PROJECTS, (k, e) -> { },
                SessionStoreFlushMode.EAGER);
        assertEquals(0, eager.maxPendingEntries());
        assertEquals(0, eager.maxPendingBytes());
        eager.enqueue(MAIN, entries("a"));
        eager.enqueue(MAIN, entries("b"));
        eager.flush();
        assertEquals(2, store.calls.size(), "eager mode appends per frame");
        batched.close();
        eager.close();
    }

    @Test
    void manyEagerFlushesKeepOrderWithoutDuplicates() {
        List<Object> seen = Collections.synchronizedList(new ArrayList<>());
        MutationFakeStore store = new MutationFakeStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) throws Exception {
                Thread.sleep(2); // widen the window for interleaving
                entries.forEach(e -> seen.add(e.get("uuid")));
                super.append(key, entries);
            }
        };
        TranscriptMirrorBatcher b = batcher(store, 0, 0, Duration.ofSeconds(5));
        List<Object> expected = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            b.enqueue(MAIN, entries("e" + i));
            expected.add("e" + i);
        }
        b.flush();
        assertEquals(expected, seen);
        b.close();
    }

    @Test
    void flushWaitsForInFlightEagerFlush() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MutationFakeStore store = new MutationFakeStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) throws Exception {
                entered.countDown();
                release.await();
                super.append(key, entries);
            }
        };
        TranscriptMirrorBatcher b = batcher(store, 0, 0, Duration.ofSeconds(5));
        b.enqueue(MAIN, entries("a"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        Thread flusher = Thread.ofVirtual().start(b::flush);
        Thread.sleep(100);
        assertTrue(flusher.isAlive(), "flush blocks behind the in-flight append");
        release.countDown();
        flusher.join(2_000);
        assertEquals(1, store.calls.size());
        b.close();
    }

    @Test
    void retriesThenSucceedsWithoutError() {
        AtomicInteger attempts = new AtomicInteger();
        MutationFakeStore store = new MutationFakeStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) throws Exception {
                if (attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("transient");
                }
                super.append(key, entries);
            }
        };
        TranscriptMirrorBatcher b = batcher(store);
        b.enqueue(MAIN, entries("a"));
        b.flush();
        assertEquals(3, attempts.get());
        assertEquals(1, store.calls.size());
        assertTrue(errors.isEmpty());
        b.close();
    }

    @Test
    void exhaustedRetriesReportOnceAndNeverThrow() {
        AtomicInteger attempts = new AtomicInteger();
        MutationFakeStore store = new MutationFakeStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) {
                attempts.incrementAndGet();
                throw new IllegalStateException("backend down");
            }
        };
        TranscriptMirrorBatcher b = new TranscriptMirrorBatcher(store, PROJECTS, (k, e) -> {
            errors.add(Map.entry(k, e));
            throw new RuntimeException("callback failure is swallowed");
        }, Duration.ofSeconds(5), 500, 1 << 20);
        b.enqueue(MAIN, entries("a"));
        b.flush();
        assertEquals(TranscriptMirrorBatcher.MAX_ATTEMPTS, attempts.get());
        assertEquals(List.of(Map.entry(MAIN_KEY, "backend down")), errors);
        b.close();
    }

    @Test
    void timeoutIsReportedAndNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        MutationFakeStore store = new MutationFakeStore() {
            @Override
            public void append(SessionKey key, List<Map<String, Object>> entries) throws Exception {
                attempts.incrementAndGet();
                Thread.sleep(2_000);
            }
        };
        TranscriptMirrorBatcher b = batcher(store, 500, 1 << 20, Duration.ofMillis(50));
        b.enqueue(MAIN, entries("a"));
        b.flush();
        assertEquals(1, attempts.get());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().getValue().contains("timed out"), errors.getFirst().getValue());
        b.close();
    }

    @Test
    void closeFlushesPendingAndLateFramesStillLand() {
        MutationFakeStore store = new MutationFakeStore();
        TranscriptMirrorBatcher b = batcher(store);
        b.enqueue(MAIN, entries("a"));
        b.close();
        assertEquals(1, store.calls.size());
        b.enqueue(MAIN, entries("late"));
        b.flush(); // after close the drain runs inline
        assertEquals(2, store.calls.size());
    }

    private static void awaitCalls(MutationFakeStore store, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (store.calls.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(count, store.calls.size());
    }
}
