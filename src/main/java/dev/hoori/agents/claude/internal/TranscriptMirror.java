package dev.hoori.agents.claude.internal;

import java.util.List;
import java.util.Map;

/** Receives {@code transcript_mirror} frames for a {@code SessionStore} (see {@code TranscriptMirrorBatcher}). */
public interface TranscriptMirror {

    void enqueue(String filePath, List<Map<String, Object>> entries);

    /** Flushes pending entries; never throws. */
    void flush();

    /** Final flush and shutdown; never throws. */
    void close();
}
