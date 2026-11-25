package dev.hoori.agents.claude.exception;

/**
 * Raised when no Claude Code CLI can be found (Python: {@code CLINotFoundError}).
 *
 * <p>This SDK never bundles the CLI: install it yourself, or point
 * {@code ClaudeAgentOptions.builder().cliPath(...)} at an existing installation.
 */
public class CliNotFoundException extends CliConnectionException {

    private final String cliPath;

    public CliNotFoundException(String message) {
        this(message, null);
    }

    public CliNotFoundException(String message, String cliPath) {
        super(cliPath == null ? message : message + ": " + cliPath);
        this.cliPath = cliPath;
    }

    /** The path that was tried, if any. */
    public String cliPath() {
        return cliPath;
    }
}
