package dev.hoori.agents.claude.exception;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * Raised when the CLI exits after reporting a terminal error result (Python: {@code ResultError}).
 *
 * <p>The CLI ends a failed run by emitting a {@code result} message with {@code is_error: true}
 * and then exiting non-zero. This exception replaces the bare "exit code 1"
 * {@link ProcessException} for that case and carries the result payload, so callers can branch
 * on <em>why</em> the run failed:
 *
 * <pre>{@code
 * try {
 *     for (Message m : ClaudeAgent.query("...", options)) { ... }
 * } catch (ResultException e) {
 *     if ("api_error".equals(e.terminalReason())) retry();
 * }
 * }</pre>
 */
public class ResultException extends ProcessException {

    private final Map<String, Object> data;

    public ResultException(String message, Map<String, Object> data, Integer exitCode) {
        super(message, exitCode, null);
        this.data = data == null ? Map.of() : Map.copyOf(withoutNulls(data));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> data) {
        var copy = new java.util.LinkedHashMap<String, Object>();
        data.forEach((k, v) -> {
            if (v != null) {
                copy.put(k, v);
            }
        });
        return copy;
    }

    /** Normalizes the {@code errors} field of a result frame to non-blank, trimmed strings. */
    public static List<String> normalizeErrors(Object raw) {
        List<?> items = raw instanceof String s ? List.of(s) : raw instanceof List<?> l ? l : List.of();
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof String s && !s.isBlank()) {
                result.add(s.strip());
            }
        }
        return List.copyOf(result);
    }

    /** The raw {@code result} message payload as emitted by the CLI. */
    public Map<String, Object> data() {
        return data;
    }

    /** The result subtype, e.g. {@code error_max_turns} (or {@code success} for API errors). */
    public String subtype() {
        return Json.string(data, "subtype");
    }

    /** Error strings reported by the CLI (may be empty). */
    public List<String> errors() {
        return normalizeErrors(data.get("errors"));
    }

    /** The result text, if any. For API failures this holds the "API Error: ..." prose. */
    public String result() {
        return Json.string(data, "result");
    }

    /** HTTP status of the failing API call, if any. */
    public Integer apiErrorStatus() {
        return Json.integer(data, "api_error_status");
    }

    /** Why the run ended (e.g. {@code api_error}, {@code max_turns}), if reported. */
    public String terminalReason() {
        return Json.string(data, "terminal_reason");
    }

    /** Session the result belongs to, if reported. */
    public String sessionId() {
        return Json.string(data, "session_id");
    }
}
