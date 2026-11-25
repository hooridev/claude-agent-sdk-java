package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.function.Supplier;

import dev.hoori.agents.claude.internal.Json;

/**
 * Seeds a temporary {@code CLAUDE_CONFIG_DIR} with the caller's auth and user config so the
 * resumed subprocess can authenticate (Python: {@code _copy_auth_files} and helpers):
 * {@code .credentials.json} (or the macOS Keychain entry) with the refresh token removed,
 * {@code .claude.json}, and {@code settings.json} / {@code cowork_settings.json} with plugin
 * declarations stripped. Every source is optional and best-effort.
 */
class ResumeConfigSeeder {

    private static final Logger LOG = System.getLogger(ResumeConfigSeeder.class.getName());
    private static final String CONFIG_DIR = "CLAUDE_CONFIG_DIR";
    /** Settings keys that would reconcile against the empty temp plugin cache on every resume. */
    private static final List<String> STRIPPED_SETTINGS_KEYS = List.of("enabledPlugins", "extraKnownMarketplaces");

    private final Map<String, String> processEnv;
    private final Path userHome;
    private final Supplier<String> keychain;

    /**
     * @param processEnv the parent's environment (lower precedence than the options' env)
     * @param userHome   home directory for {@code ~/.claude} and {@code ~/.claude.json}
     * @param keychain   reads the macOS Keychain credentials ({@code null} result = none)
     */
    ResumeConfigSeeder(Map<String, String> processEnv, Path userHome, Supplier<String> keychain) {
        this.processEnv = processEnv;
        this.userHome = userHome;
        this.keychain = keychain;
    }

    /** The real process environment (with the {@code claude.configDir} property hook) and home. */
    static ResumeConfigSeeder system() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String override = System.getProperty(SessionPaths.CONFIG_DIR_PROPERTY);
        if (override != null && !override.isEmpty()) {
            env.put(CONFIG_DIR, override);
        }
        return new ResumeConfigSeeder(env, Path.of(System.getProperty("user.home")), MacKeychain::readCredentials);
    }

    /** Copies the auth/config files into {@code tmpBase}. */
    void seed(Path tmpBase, Map<String, String> optionEnv) {
        String callerConfigDir = lookup(optionEnv, CONFIG_DIR);
        Path sourceConfigDir = callerConfigDir != null ? Path.of(callerConfigDir) : userHome.resolve(".claude");

        byte[] credentials = readIfPresent(sourceConfigDir.resolve(".credentials.json"));
        String credentialsJson = credentials != null ? new String(credentials, StandardCharsets.UTF_8) : null;
        // macOS keeps OAuth tokens in the Keychain under a service name derived from the config dir;
        // the redirected subprocess would miss it, so hand it over as a plain-text credentials file.
        if (callerConfigDir == null && lookup(optionEnv, "ANTHROPIC_API_KEY") == null
                && lookup(optionEnv, "CLAUDE_CODE_OAUTH_TOKEN") == null) {
            String fromKeychain = keychain.get();
            if (fromKeychain != null) {
                credentialsJson = fromKeychain;
            }
        }
        writeRedactedCredentials(credentialsJson, tmpBase.resolve(".credentials.json"));

        Path claudeJson = callerConfigDir != null
                ? Path.of(callerConfigDir).resolve(".claude.json")
                : userHome.resolve(".claude.json");
        copyIfPresent(claudeJson, tmpBase.resolve(".claude.json"), UnaryOperator.identity());
        for (String name : List.of("settings.json", "cowork_settings.json")) {
            copyIfPresent(sourceConfigDir.resolve(name), tmpBase.resolve(name), ResumeConfigSeeder::stripSettings);
        }
    }

    private String lookup(Map<String, String> optionEnv, String name) {
        String value = optionEnv != null ? optionEnv.get(name) : null;
        if (value == null || value.isEmpty()) {
            value = processEnv.get(name);
        }
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * Writes the credentials without {@code claudeAiOauth.refreshToken}: a refresh in the
     * subprocess would consume the single-use token and revoke the parent's stored credentials.
     */
    static void writeRedactedCredentials(String credentialsJson, Path target) {
        if (credentialsJson == null) {
            return;
        }
        String out = credentialsJson;
        try {
            Map<String, Object> data = Json.parseObject(credentialsJson);
            Map<String, Object> oauth = Json.object(data, "claudeAiOauth");
            if (oauth != null && oauth.containsKey("refreshToken")) {
                oauth.remove("refreshToken");
                out = Json.write(data);
            }
        } catch (RuntimeException ignored) {
            // unparseable: write through, the subprocess will fail to parse it too
        }
        try {
            FileOps.writePrivate(target, out.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[SessionStore] resume: could not write " + target + " (" + e + ")");
        }
    }

    /**
     * Drops {@code enabledPlugins}, {@code extraKnownMarketplaces} and {@code env.CLAUDE_CONFIG_DIR}.
     * Content that is not a JSON object (or would not re-serialize faithfully) is returned as is.
     */
    static byte[] stripSettings(byte[] content) {
        Map<String, Object> parsed;
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            if (text.startsWith("﻿")) {
                text = text.substring(1); // PowerShell writes settings.json with a BOM
            }
            parsed = Json.parseObject(text);
        } catch (CharacterCodingException | RuntimeException e) {
            return content;
        }
        boolean stripped = false;
        for (String key : STRIPPED_SETTINGS_KEYS) {
            if (parsed.containsKey(key)) {
                parsed.remove(key);
                stripped = true;
            }
        }
        Map<String, Object> env = Json.object(parsed, "env");
        if (env != null && env.containsKey(CONFIG_DIR)) {
            env.remove(CONFIG_DIR);
            stripped = true;
        }
        if (!stripped || containsNonFinite(parsed)) {
            return content; // e.g. 1e999 would re-serialize as a token the CLI rejects
        }
        return Json.write(parsed).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean containsNonFinite(Object value) {
        return switch (value) {
            case Double d -> !Double.isFinite(d);
            case Float f -> !Float.isFinite(f);
            case Map<?, ?> map -> map.values().stream().anyMatch(v -> v != null && containsNonFinite(v));
            case List<?> list -> list.stream().anyMatch(v -> v != null && containsNonFinite(v));
            default -> false;
        };
    }

    /** Reads a regular file; missing → {@code null}, anything else unreadable → logged and {@code null}. */
    private static byte[] readIfPresent(Path source) {
        try {
            if (!Files.exists(source)) {
                return null;
            }
            if (!Files.isRegularFile(source)) {
                throw new IOException(source + " is not a regular file"); // never block on a FIFO
            }
            return Files.readAllBytes(source);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "[SessionStore] resume: skipping " + source + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private static void copyIfPresent(Path source, Path target, UnaryOperator<byte[]> transform) {
        byte[] content = readIfPresent(source);
        if (content == null) {
            return;
        }
        try {
            FileOps.writePrivate(target, transform.apply(content));
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target); // no truncated file for the subprocess to misparse
            } catch (IOException ignored) {
                // best-effort
            }
            LOG.log(Level.WARNING, "[SessionStore] resume: skipping " + source + " (" + e.getMessage() + ")");
        }
    }
}
