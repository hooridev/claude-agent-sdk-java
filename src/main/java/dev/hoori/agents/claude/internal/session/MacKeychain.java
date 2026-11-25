package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Reads the CLI's OAuth credentials from the macOS Keychain ({@code security find-generic-password}). */
final class MacKeychain {

    /** Default service name when {@code CLAUDE_CONFIG_DIR} is unset. */
    static final String SERVICE_NAME = "Claude Code-credentials";

    private MacKeychain() {
    }

    /** The credentials JSON, or {@code null} on non-macOS hosts and on any error. Best-effort. */
    static String readCredentials() {
        if (!System.getProperty("os.name", "").startsWith("Mac")) {
            return null;
        }
        String user = System.getenv("USER");
        if (user == null || user.isEmpty()) {
            user = System.getProperty("user.name", "claude-code-user");
        }
        try {
            Process process = new ProcessBuilder("security", "find-generic-password", "-a", user, "-w",
                    "-s", SERVICE_NAME)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // The output is far below the pipe buffer size, so waiting before reading cannot deadlock.
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (InputStream in = process.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
                return out.isEmpty() ? null : out;
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
