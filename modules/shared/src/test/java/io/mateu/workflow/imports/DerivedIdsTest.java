package io.mateu.workflow.imports;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The id a file gets when it declares none.
 *
 * <p>The property under test is the one the old random UUID lacked: the same file gives the same id
 * next time. Everything else here is about not taking an id that belongs to somebody else.
 */
class DerivedIdsTest {

    @Test
    void the_id_is_the_path_relative_to_the_scan_root(@TempDir Path root) {
        assertThat(DerivedIds.forFile(root, root.resolve("onboarding.ec"))).isEqualTo("onboarding");
        assertThat(DerivedIds.forFile(root, root.resolve("sagas/onboarding.ec")))
                .isEqualTo("sagas.onboarding");
        assertThat(DerivedIds.forFile(root, root.resolve("a/b/c/deep.json"))).isEqualTo("a.b.c.deep");
    }

    @Test
    void the_editors_double_extension_is_one_extension() {
        var root = Path.of("/repo");
        // What the plugins write. onboarding.yml.ec is one file, not a file called onboarding.yml.
        assertThat(DerivedIds.forFile(root, Path.of("/repo/onboarding.yml.ec"))).isEqualTo("onboarding");
        assertThat(DerivedIds.forFile(root, Path.of("/repo/checkin.ecform"))).isEqualTo("checkin");
    }

    @Test
    void a_suffix_that_is_not_an_extension_is_part_of_the_name() {
        // The author put the version in the file name on purpose; it is part of what identifies it.
        assertThat(DerivedIds.forFile(Path.of("/repo"), Path.of("/repo/order.v2.ec")))
                .isEqualTo("order.v2");
    }

    @Test
    void the_same_file_gives_the_same_id_every_time() {
        var root = Path.of("/repo");
        var file = Path.of("/repo/sagas/onboarding.ec");

        // The whole point. A random UUID here is what made an import unable to find what the
        // previous import had created from this very file.
        assertThat(DerivedIds.forFile(root, file)).isEqualTo(DerivedIds.forFile(root, file));
    }

    @Test
    void it_reads_the_ids_the_scan_declares_before_anything_is_imported(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("sagas"));
        Files.writeString(root.resolve("sagas/onboarding.ec"), "{\"id\":\"declared-elsewhere\"}");
        Files.writeString(root.resolve("nameless.ec"), "{\"name\":\"no id here\"}");
        Files.writeString(root.resolve("notes.md"), "not a definition");

        var declared = DerivedIds.declaredUnder(root, path -> path.toString().endsWith(".ec"),
                file -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile()));

        assertThat(declared).containsExactly("declared-elsewhere");
    }

    @Test
    void a_file_it_cannot_read_is_left_to_the_import_to_report(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("broken.ec"), "{ this is not json");
        Files.writeString(root.resolve("fine.ec"), "{\"id\":\"fine\"}");

        var declared = DerivedIds.declaredUnder(root, path -> path.toString().endsWith(".ec"),
                file -> new com.fasterxml.jackson.databind.ObjectMapper().readTree(file.toFile()));

        // Not this pass's problem: the import pass reports it with the file name and the reason.
        assertThat(declared).containsExactly("fine");
    }

    @Test
    void a_derived_id_never_takes_one_that_is_already_spoken_for() {
        assertThatThrownBy(() ->
                DerivedIds.refuseIfTaken("sagas.onboarding", Set.of("sagas.onboarding"), Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sagas.onboarding")
                .hasMessageContaining("give this file an explicit id");

        assertThatThrownBy(() ->
                DerivedIds.refuseIfTaken("sagas.onboarding", Set.of(), Set.of("sagas.onboarding")))
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> DerivedIds.refuseIfTaken("sagas.onboarding", Set.of("other"), Set.of("another")))
                .doesNotThrowAnyException();
    }
}
