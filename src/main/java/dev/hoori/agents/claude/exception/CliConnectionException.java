package dev.hoori.agents.claude.exception;

/** Raised when the SDK cannot connect to, or talk to, Claude Code (Python: {@code CLIConnectionError}). */
public class CliConnectionException extends ClaudeSdkException {

    public CliConnectionException(String message) {
        super(message);
    }

    public CliConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
