package io.mateu.workflow.infra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The hot registry: parses commas/newlines/comments, reloads on change, and keeps the last good list
 *  when the file cannot be read. */
class FileShardRegistryTest {

    @Test
    void parsesCommasNewlinesAndComments() {
        assertThat(FileShardRegistry.parse(List.of("s0, s1  # inline note", "# whole-line comment", "", " s2 ")))
                .containsExactly("s0", "s1", "s2");
    }

    @Test
    void reflectsFileChangesOnReload(@TempDir Path dir) throws IOException {
        var file = dir.resolve("shards");
        Files.writeString(file, "s0,s1");
        var registry = new FileShardRegistry(file.toString(), 999_999);
        assertThat(registry.activeShards()).containsExactly("s0", "s1");

        // Scale up: add s2, remove s0 — the change takes effect on the next reload, no restart.
        Files.writeString(file, "s1\ns2\n");
        registry.reload();
        assertThat(registry.activeShards()).containsExactly("s1", "s2");
    }

    @Test
    void keepsTheLastKnownListWhenTheFileCannotBeRead(@TempDir Path dir) throws IOException {
        var file = dir.resolve("shards");
        Files.writeString(file, "s0,s1");
        var registry = new FileShardRegistry(file.toString(), 999_999);

        Files.delete(file);
        registry.reload();

        // Not drained to empty — a transient read failure must never route the whole fleet to nowhere.
        assertThat(registry.activeShards()).containsExactly("s0", "s1");
    }
}
