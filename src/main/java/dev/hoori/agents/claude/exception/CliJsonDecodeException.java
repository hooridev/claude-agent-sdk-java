package dev.hoori.agents.claude.exception;

/** Raised when a line of CLI output cannot be decoded as JSON (Python: {@code CLIJSONDecodeError}). */
public class CliJsonDecodeException extends ClaudeSdkException {

    private final String line;

    public CliJsonDecodeException(String line, Throwable originalError) {
        super("Failed to decode JSON: " + (line.length() > 100 ? line.substring(0, 100) : line) + "...",
                originalError);
        this.line = line;
    }

    /** The offending line. */
    public String line() {
        return line;
    }
}
