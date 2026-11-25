package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.hoori.agents.claude.internal.Json;

/**
 * An isolated config dir (via the {@code claude.configDir} property, never the real
 * {@code ~/.claude}) with one project directory. Close it to clear the property.
 */
public final class MutationTestFixture implements AutoCloseable {

    public final Path configDir;
    public final Path cwd;
    public final Path sessionsDir;

    public MutationTestFixture(Path tmp) {
        try {
            configDir = Files.createDirectories(tmp.resolve("config"));
            cwd = Files.createDirectories(tmp.resolve("project"));
            System.setProperty(SessionPaths.CONFIG_DIR_PROPERTY, configDir.toString());
            sessionsDir = Files.createDirectories(configDir.resolve("projects")
                    .resolve(SessionPaths.sanitizePath(SessionPaths.canonicalizePath(cwd.toString()))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The project path as passed to the mutation API. */
    public String directory() {
        return cwd.toString();
    }

    public Path writeSession(String sessionId, List<Map<String, Object>> entries) {
        StringBuilder jsonl = new StringBuilder();
        entries.forEach(e -> jsonl.append(Json.write(e)).append('\n'));
        return writeRaw(sessionsDir.resolve(sessionId + ".jsonl"), jsonl.toString());
    }

    public static Path writeRaw(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            return Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Map<String, Object>> readEntries(Path file) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(Json.parseObject(line));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A transcript entry. */
    public static Map<String, Object> entry(String type, String uuid, String parentUuid, Object... extra) {
        Map<String, Object> e = Json.obj("type", type, "uuid", uuid, "parentUuid", parentUuid);
        for (int i = 0; i < extra.length; i += 2) {
            e.put((String) extra[i], extra[i + 1]);
        }
        return e;
    }

    @Override
    public void close() {
        System.clearProperty(SessionPaths.CONFIG_DIR_PROPERTY);
    }
}
