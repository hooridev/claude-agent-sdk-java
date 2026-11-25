package dev.hoori.agents.claude.session;

/**
 * Session metadata returned by the session listing functions (Python: {@code SDKSessionInfo}).
 *
 * @param sessionId    the session UUID
 * @param summary      display title: custom title, auto-generated summary, or first prompt
 * @param lastModified last modified time in epoch milliseconds
 * @param fileSize     JSONL size in bytes; only for local storage, else {@code null}
 * @param customTitle  user-set or AI-generated title
 * @param firstPrompt  first meaningful user prompt
 * @param gitBranch    git branch at the end of the session
 * @param cwd          working directory of the session
 * @param tag          user-set tag
 * @param createdAt    creation time in epoch milliseconds, from the first entry's timestamp
 */
public record SessionInfo(
        String sessionId,
        String summary,
        long lastModified,
        Long fileSize,
        String customTitle,
        String firstPrompt,
        String gitBranch,
        String cwd,
        String tag,
        Long createdAt) {
}
