package io.mateu.workflow.imports;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The id a definition file gets when it declares none.
 *
 * <p>It used to be a fresh {@code UUID.randomUUID()} per import, which quietly made the file
 * unreconcilable: nothing connected the row an import produced to the row the previous import
 * produced from the same file, so every import inserted another copy and none of them could be
 * pruned — the import code said as much, and only tracked explicit ids for pruning. With a git
 * webhook wired up, every push added a copy, without bound.
 *
 * <p>What reconciliation needs is an id that is the same next time, and the file's own path is
 * exactly that. So the path is the id: {@code sagas/onboarding.ec} under the scan root becomes
 * {@code sagas.onboarding}. Dots rather than slashes because an id travels in URLs and in event
 * payloads, where a slash is a separator.
 *
 * <p>Two things follow from it, and they are choices rather than accidents:
 *
 * <ul>
 *   <li><b>Moving or renaming a file is a delete plus a create.</b> The old path stops being
 *       imported, so it is pruned; the new one arrives as a new definition. Defensible — a
 *       definition with no id of its own is identified by where it lives, and it has moved — but it
 *       is a real consequence, and a file that must survive a move should declare an {@code id}.
 *   <li><b>The id is relative to the scan root</b>, so changing {@code directory} in the import
 *       configuration changes the ids of every file that declares none, exactly as moving them
 *       would.
 * </ul>
 *
 * <p>An explicit {@code id} always wins, and a derived id never takes one: {@link #declaredUnder}
 * reads what the scan declares before anything is imported, so a file whose path would produce an
 * id another file claims is refused rather than silently overwriting it.
 */
public final class DerivedIds {

    private DerivedIds() {
    }

    /** Reads one candidate file into a tree, in whatever format that engine's files are written. */
    @FunctionalInterface
    public interface FileReader {
        JsonNode read(Path file) throws IOException;
    }

    /**
     * The extensions a definition file is written with, stripped from the derived id.
     *
     * <p>Only these, and repeatedly: {@code onboarding.yml.ec} is a name the editors write, and it
     * means one file, not a file called {@code onboarding.yml}. A suffix that is not one of these is
     * part of the name and stays — {@code order.v2.ec} derives {@code order.v2}, because the author
     * put the version there on purpose.
     */
    private static final List<String> DEFINITION_EXTENSIONS =
            List.of(".ec", ".ecform", ".ecrule", ".json", ".yaml", ".yml");

    /**
     * The stable id for a file that declares none: its path relative to the scan root, without the
     * definition extensions, with the separators turned into dots.
     */
    public static String forFile(Path scanRoot, Path file) {
        var relative = scanRoot.relativize(file).toString().replace('\\', '/');
        for (var stripped = true; stripped; ) {
            stripped = false;
            for (var extension : DEFINITION_EXTENSIONS) {
                if (relative.length() > extension.length() && relative.endsWith(extension)) {
                    relative = relative.substring(0, relative.length() - extension.length());
                    stripped = true;
                }
            }
        }
        return relative.replace('/', '.');
    }

    /**
     * Every id the files under this root declare explicitly.
     *
     * <p>Collected before anything is imported because the collision it guards against is one of
     * order: a file that declares {@code sagas.onboarding} may be walked after the file whose path
     * derives the same id, and by then the derived one would already have been saved over — which is
     * a worse failure than the duplication this whole change is about.
     *
     * <p>A file that cannot be read is not this pass's problem: it is left out, and the import pass
     * reports it properly with the file name and the reason.
     */
    public static Set<String> declaredUnder(Path scanRoot, Predicate<Path> isDefinitionFile,
                                            FileReader reader) throws IOException {
        var declared = new LinkedHashSet<String>();
        try (var stream = Files.walk(scanRoot)) {
            stream.filter(isDefinitionFile).forEach(file -> {
                try {
                    var node = reader.read(file);
                    if (node != null && node.hasNonNull("id") && !node.get("id").asText("").isBlank()) {
                        declared.add(node.get("id").asText());
                    }
                } catch (Exception ignored) {
                    // Unreadable here, reported there.
                }
            });
        }
        return declared;
    }

    /**
     * Refuses a derived id that is already spoken for, by an explicit id anywhere in the scan or by
     * a file already imported in it.
     *
     * @throws IllegalStateException naming the id and what to do about it
     */
    public static void refuseIfTaken(String derivedId, Set<String> declaredIds, Set<String> importedIds) {
        if (declaredIds.contains(derivedId) || importedIds.contains(derivedId)) {
            throw new IllegalStateException("its path gives it the id '" + derivedId
                    + "', which another definition in this import already uses; give this file an"
                    + " explicit id, or move it");
        }
    }
}
