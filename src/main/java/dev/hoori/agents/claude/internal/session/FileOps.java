package dev.hoori.agents.claude.internal.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

/** File helpers of the session mutation and resume modules: owner-only writes and tree deletion. */
final class FileOps {

    private static final Set<PosixFilePermission> OWNER_RW = PosixFilePermissions.fromString("rw-------");

    private FileOps() {
    }

    /** Creates {@code path} (failing if it exists) with mode 0600 and writes {@code content}. */
    static void writeNewPrivate(Path path, byte[] content) throws IOException {
        try {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_RW));
        } catch (UnsupportedOperationException e) {
            Files.createFile(path); // non-POSIX file system
        }
        Files.write(path, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Writes (creating or replacing) {@code path}, then restricts it to mode 0600 (best-effort). */
    static void writePrivate(Path path, byte[] content) throws IOException {
        Files.write(path, content);
        restrictToOwner(path);
    }

    /** Best-effort chmod 0600. */
    static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_RW);
        } catch (IOException | UnsupportedOperationException ignored) {
            // permissions are hardening only
        }
    }

    /** Deletes a file tree; a missing tree is fine. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(path);
                } catch (NoSuchFileException ignored) {
                    // already gone
                }
            }
        } catch (NoSuchFileException ignored) {
            // removed concurrently
        }
    }

    /** {@link #deleteTree} ignoring every error. */
    static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException | RuntimeException ignored) {
            // best-effort
        }
    }
}
