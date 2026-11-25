package dev.hoori.agents.claude.exception;

/** Base exception for all Claude Agent SDK errors (Python: {@code ClaudeSDKError}). */
public class ClaudeSdkException extends RuntimeException {

    public ClaudeSdkException(String message) {
        super(message);
    }

    public ClaudeSdkException(String message, Throwable cause) {
        super(message, cause);
    }
}
