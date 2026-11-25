package dev.hoori.agents.claude.exception;

/** Raised when the CLI process fails (Python: {@code ProcessError}). */
public class ProcessException extends ClaudeSdkException {

    private final Integer exitCode;
    private final String stderr;

    public ProcessException(String message, Integer exitCode, String stderr) {
        super(format(message, exitCode, stderr));
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    private static String format(String message, Integer exitCode, String stderr) {
        String result = message;
        if (exitCode != null) {
            result += " (exit code: " + exitCode + ")";
        }
        if (stderr != null && !stderr.isEmpty()) {
            result += "\nError output: " + stderr;
        }
        return result;
    }

    /** Exit code of the CLI process, if known. */
    public Integer exitCode() {
        return exitCode;
    }

    /** Captured stderr, if any. */
    public String stderr() {
        return stderr;
    }
}
