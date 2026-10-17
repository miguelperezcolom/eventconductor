package io.mateu.workflow.imports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * How an import reads the files of a directory or a cloned repository — which are untrusted: a
 * repository can hold anything its committers put there.
 *
 * <ul>
 *   <li><b>Symbolic links are skipped.</b> Git stores a symlink as a link, and a clone recreates it,
 *       so a {@code flow.json} pointing at {@code /etc/passwd} or at the app's own secrets would
 *       otherwise be read and its content echoed back in an import error.</li>
 *   <li><b>A file must really be under the import root</b> (its real path inside the root's real
 *       path) — a belt to the braces above.</li>
 *   <li><b>Each file is read up to {@link #MAX_FILE_BYTES}</b>; a larger one is refused with an
 *       error naming the file, instead of being loaded into the heap whole.</li>
 * </ul>
 */
public final class ImportFiles {

    /** The largest file an import reads: far beyond any definition, short of a memory problem. */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(ImportFiles.class);

    private ImportFiles() {
    }

    /**
     * The regular files under {@code root}, symbolic links and anything resolving outside the root
     * left out. Close the stream (try-with-resources), as with {@link Files#walk}.
     */
    public static Stream<Path> walk(Path root) throws IOException {
        var realRoot = root.toRealPath();
        return Files.walk(root).filter(path -> isImportable(realRoot, root, path));
    }

    private static boolean isImportable(Path realRoot, Path root, Path path) {
        if (Files.isSymbolicLink(path)) {
            log.warn("Import skips {}: it is a symbolic link", root.relativize(path));
            return false;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            if (!path.toRealPath().startsWith(realRoot)) {
                log.warn("Import skips {}: it resolves outside the import root", root.relativize(path));
                return false;
            }
        } catch (IOException unreadable) {
            return false;
        }
        return true;
    }

    /**
     * The content of {@code file}, read without following a symbolic link and up to
     * {@link #MAX_FILE_BYTES}.
     *
     * @throws IOException for a symbolic link, or a file over the limit (the message says which)
     */
    public static byte[] read(Path file) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("it is a symbolic link, which an import does not follow");
        }
        try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = in.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IOException("it is larger than " + (MAX_FILE_BYTES / (1024 * 1024))
                        + " MB, the most an import reads from one file");
            }
            return bytes;
        }
    }
}
