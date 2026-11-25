package dev.hoori.agents.claude.exception;

import java.util.Map;

/** Raised when a CLI message cannot be parsed into a typed message (Python: {@code MessageParseError}). */
public class MessageParseException extends ClaudeSdkException {

    private final transient Map<String, Object> data;

    public MessageParseException(String message, Map<String, Object> data) {
        super(message);
        this.data = data;
    }

    /** The raw message that failed to parse. */
    public Map<String, Object> data() {
        return data;
    }
}
