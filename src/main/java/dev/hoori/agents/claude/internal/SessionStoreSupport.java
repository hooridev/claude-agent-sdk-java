package dev.hoori.agents.claude.internal;

import dev.hoori.agents.claude.ClaudeAgentOptions;
import dev.hoori.agents.claude.internal.session.MaterializedResume;
import dev.hoori.agents.claude.internal.session.SessionPaths;
import dev.hoori.agents.claude.internal.session.SessionResume;
import dev.hoori.agents.claude.internal.session.TranscriptMirrorBatcher;
import dev.hoori.agents.claude.session.SessionStore;
import dev.hoori.agents.claude.session.SessionStores;

/** {@code SessionStore} integration of a connection: option validation, store-backed resume and mirroring. */
final class SessionStoreSupport {

    private SessionStoreSupport() {
    }

    /** Fails fast on invalid option combinations (Python: {@code validate_session_store_options}). */
    static void validate(ClaudeAgentOptions options) {
        SessionStore store = options.sessionStore();
        if (store == null) {
            return;
        }
        if (options.continueConversation() && options.resume() == null
                && !SessionStores.implementsMethod(store, "listSessions")) {
            throw new IllegalArgumentException(
                    "continueConversation with sessionStore requires the store to implement listSessions()");
        }
        if (options.enableFileCheckpointing()) {
            throw new IllegalArgumentException("sessionStore cannot be combined with enableFileCheckpointing "
                    + "(checkpoints are local-disk only and would diverge from the mirrored transcript)");
        }
    }

    /**
     * Materializes a store-backed resume into a temporary config directory when
     * {@code materialize} is set and the options pair a store with {@code resume} or
     * {@code continueConversation}.
     *
     * @throws dev.hoori.agents.claude.exception.ClaudeSdkException if loading from the store fails
     */
    static Prepared prepare(ClaudeAgentOptions options, boolean materialize) {
        return new Prepared(materialize ? SessionResume.materialize(options) : null);
    }

    /** The prepared store integration of one connection. */
    static final class Prepared {

        private final MaterializedResume materialized;

        private Prepared(MaterializedResume materialized) {
            this.materialized = materialized;
        }

        /** Points the options at the materialized directory (if any). */
        ClaudeAgentOptions apply(ClaudeAgentOptions options) {
            return materialized == null ? options : SessionResume.apply(options, materialized);
        }

        /** Attaches a transcript mirror batcher when a store is configured; failures surface as mirror_error. */
        void attachMirror(ControlProtocol protocol, ClaudeAgentOptions options) {
            SessionStore store = options.sessionStore();
            if (store == null) {
                return;
            }
            // Keys are derived relative to the projects dir the subprocess actually writes to.
            String projectsDir = materialized != null
                    ? materialized.configDir().resolve("projects").toString()
                    : SessionPaths.projectsDir(options.env()).toString();
            protocol.setTranscriptMirror(TranscriptMirrorBatcher.create(store, projectsDir,
                    (key, error) -> protocol.reportMirrorError(key == null ? null : key.toMap(), error),
                    options.sessionStoreFlush()));
        }

        /** Removes the temporary config directory (if any); call after the subprocess is closed. */
        void cleanup() {
            if (materialized != null) {
                materialized.cleanup();
            }
        }
    }
}
