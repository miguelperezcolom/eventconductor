package io.mateu.workflow;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two migrations sharing a version number make Flyway refuse to start — "Found more than one
 * migration with version 11" — and the application dies at boot with no schema. It happened:
 * two branches each added a V11 and both merged green, because nothing on the build path looks
 * at these filenames. Compilation does not, and a Spring context test does not either unless it
 * actually runs Flyway against a database.
 *
 * <p>This is a filename check on purpose. It needs no database, so it cannot be skipped for the
 * usual reason integration tests get skipped.
 */
class MigrationVersionsAreUniqueTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/workflow");

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    @Test
    void everyMigrationHasItsOwnVersion() throws IOException {
        Map<String, List<String>> byVersion;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            byVersion = files
                    .map(p -> p.getFileName().toString())
                    .map(VERSIONED::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.groupingBy(
                            m -> m.group(1).replace('_', '.'),
                            Collectors.mapping(Matcher::group, Collectors.toList())));
        }

        var collisions = byVersion.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> "V" + e.getKey() + " -> " + e.getValue())
                .toList();

        assertTrue(collisions.isEmpty(),
                "Flyway will not start with duplicate migration versions: " + collisions);
    }

    @Test
    void thereIsAtLeastOneMigrationToFind() throws IOException {
        // Guards the guard: a wrong path would make the test above pass on an empty stream, and
        // the check that matters would quietly stop checking anything.
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            assertTrue(files.anyMatch(p -> VERSIONED.matcher(p.getFileName().toString()).matches()),
                    "No versioned migrations found under " + MIGRATIONS.toAbsolutePath()
                            + " — has the location moved?");
        }
    }
}
