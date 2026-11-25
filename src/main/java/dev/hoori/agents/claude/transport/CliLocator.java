package dev.hoori.agents.claude.transport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import dev.hoori.agents.claude.exception.CliNotFoundException;

/**
 * Finds an installed Claude Code CLI. The SDK never bundles the CLI; it must be installed
 * separately (see https://docs.claude.com/en/docs/claude-code).
 */
final class CliLocator {

    private CliLocator() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Searches PATH, then common installation directories.
     *
     * @throws CliNotFoundException if nothing is found
     */
    static Path find() {
        Path home = Path.of(System.getProperty("user.home"));
        String executable = isWindows() ? "claude.exe" : "claude";
        // Only absolute PATH entries: a relative entry would resolve against the working directory.
        for (String entry : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path dir = Path.of(entry);
            Path candidate = dir.resolve(executable);
            if (dir.isAbsolute() && Files.isRegularFile(candidate) && (isWindows() || Files.isExecutable(candidate))) {
                return candidate;
            }
        }
        List<Path> locations = isWindows()
                ? List.of(home.resolve(".local/bin/claude.exe"))
                : List.of(
                home.resolve(".npm-global/bin/claude"),
                Path.of("/usr/local/bin/claude"),
                home.resolve(".local/bin/claude"),
                home.resolve("node_modules/.bin/claude"),
                home.resolve(".yarn/bin/claude"),
                home.resolve(".claude/local/claude"));
        for (Path location : locations) {
            if (Files.isRegularFile(location)) {
                return location;
            }
        }
        throw new CliNotFoundException(isWindows()
                ? "Claude Code not found. Install the native claude.exe (PowerShell):\n"
                + "  irm https://claude.ai/install.ps1 | iex\n"
                + "\nor set ClaudeAgentOptions.builder().cliPath(\"C:\\\\path\\\\to\\\\claude.exe\")."
                : "Claude Code not found. Install it with:\n"
                + "  curl -fsSL https://claude.ai/install.sh | bash\n"
                + "  (or: npm install -g @anthropic-ai/claude-code)\n"
                + "\nor set ClaudeAgentOptions.builder().cliPath(\"/path/to/claude\").");
    }
}
