package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.infra.config.GitImportProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Scoping the scan to a subdirectory of the clone.
 *
 * <p>GitImportProperties.GitRepository had no `directory` field, while the workflow engine's copy
 * of the same class did — so `FORMS_GITIMPORT_REPOSITORIES_0_DIRECTORY` was accepted by relaxed
 * binding and then did nothing. Pointed at a repository that is not exclusively definitions, the
 * import walked the entire clone: a parse error per unrelated YAML file, and anything that happened
 * to look like a definition imported as one.
 *
 * <p>The escape guard is the part worth more than its size. The directory comes from configuration,
 * and the root it is resolved against is the throwaway clone the import deletes afterwards, so a
 * `directory: ../..` that normalised instead of being refused would walk — and prune against —
 * whatever it found outside.
 */
class FormsGitImportScanTest {

    private final io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase
            directoryImport = mock(
                    io.mateu.workflow.application.usecases.directoryimport.ImportFormsFromDirectoryUseCase.class);

    private final ImportFormsFromGitUseCase useCase = new ImportFormsFromGitUseCase(
            mock(GitImportProperties.class), mock(io.mateu.workflow.application.out.FormsMetrics.class),
            directoryImport);

    private static GitImportProperties.GitRepository repo(String directory) {
        var repo = new GitImportProperties.GitRepository();
        repo.setUrl("https://github.com/org/defs.git");
        repo.setDirectory(directory);
        return repo;
    }

    @Test
    void noDirectoryMeansScanTheWholeRepository(@TempDir Path root) throws IOException {
        assertThat(useCase.resolveScanRoot(repo(null), root)).isEqualTo(root);
        assertThat(useCase.resolveScanRoot(repo("   "), root)).isEqualTo(root);
    }

    @Test
    void aDeclaredSubdirectoryNarrowsTheScan(@TempDir Path root) throws IOException {
        var definitions = Files.createDirectories(root.resolve("definitions"));

        assertThat(useCase.resolveScanRoot(repo("definitions"), root)).isEqualTo(definitions);
    }

    @Test
    void aDirectoryThatClimbsOutOfTheRepositoryIsRefused(@TempDir Path root) {
        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("../.."), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the repository root");

        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("definitions/../../.."), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes the repository root");
    }

    @Test
    void aDirectoryThatIsNotThereIsAnErrorRatherThanAnEmptyImport(@TempDir Path root) {
        // Silently importing nothing would read as "the repository has no definitions", which is
        // the same thing a typo in the mount path produces.
        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("nope"), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found in repository");
    }

    @Test
    void twoEntriesOnOneRepositoryPruneIndependently() {
        // Same URL, different subdirectories: folding the directory into the prune key is what
        // stops one of them pruning the other's definitions on every import.
        assertThat(ImportFormsFromGitUseCase.pruneKey(repo(null)))
                .isEqualTo("https://github.com/org/defs.git");
        assertThat(ImportFormsFromGitUseCase.pruneKey(repo("a")))
                .isNotEqualTo(ImportFormsFromGitUseCase.pruneKey(repo("b")));
    }

    /**
     * The helpers above are only worth having if the import calls them. This is the wiring the bug
     * actually was: the field existed nowhere, so the clone root and the bare URL went straight
     * through and the configured directory changed nothing.
     */
    @Test
    void theImportScansTheConfiguredSubdirectoryAndPrunesUnderItsOwnKey(@TempDir Path clone)
            throws Exception {
        var forms = Files.createDirectories(clone.resolve("forms"));
        var repo = repo("forms");

        useCase.importFromClone(repo, clone, new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                new java.util.ArrayList<>());

        verify(directoryImport).importFrom(
                org.mockito.ArgumentMatchers.eq(forms),
                org.mockito.ArgumentMatchers.eq("https://github.com/org/defs.git#forms"),
                any(), any(), any());
    }
}
