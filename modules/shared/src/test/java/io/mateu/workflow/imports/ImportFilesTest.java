package io.mateu.workflow.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What an import may read from a directory or a clone: plain files under its root, of a sane size. */
class ImportFilesTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void symbolicLinksAreNotWalkedNorRead(@TempDir Path root, @TempDir Path outside) throws Exception {
        Files.writeString(root.resolve("real.json"), "{}");
        var secret = Files.writeString(outside.resolve("secret.json"), "{\"password\":\"x\"}");
        Files.createSymbolicLink(root.resolve("leak.json"), secret);
        Files.createDirectories(outside.resolve("dir"));
        Files.writeString(outside.resolve("dir/inner.json"), "{}");
        Files.createSymbolicLink(root.resolve("linked-dir"), outside.resolve("dir"));

        try (var files = ImportFiles.walk(root)) {
            assertThat(files.map(f -> root.relativize(f).toString()).toList()).containsExactly("real.json");
        }
        assertThatThrownBy(() -> ImportFiles.read(root.resolve("leak.json")))
                .isInstanceOf(IOException.class).hasMessageContaining("symbolic link");
    }

    @Test
    void aFileOverTheLimitIsRefusedNotLoaded(@TempDir Path root) throws Exception {
        var big = root.resolve("big.json");
        Files.write(big, new byte[(int) ImportFiles.MAX_FILE_BYTES + 1]);
        assertThatThrownBy(() -> ImportFiles.read(big)).isInstanceOf(IOException.class).hasMessageContaining("larger than 5 MB");

        var exact = root.resolve("exact.json");
        Files.write(exact, new byte[(int) ImportFiles.MAX_FILE_BYTES]);
        assertThat(ImportFiles.read(exact)).hasSize((int) ImportFiles.MAX_FILE_BYTES);
    }
}
