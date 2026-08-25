package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.usecases.directoryimport.ImportWorkflowDefinitionsFromDirectoryUseCase;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The half of import that touches the filesystem rather than the network — the clone's scan root,
 * and the directory walk a clone shares with a plain directory.
 *
 * <p>Two things here are worth more than their size. {@code resolveScanRoot} carries a path-escape
 * guard: the scanned directory comes from configuration, and without the guard a
 * {@code directory: ../../..} walks out of the throwaway clone and imports — or, through the same
 * root, deletes — whatever it finds. And {@code scanAndImport} decides what one bad file costs: a
 * single unparseable definition in a repository of fifty must be reported and stepped over, not
 * allowed to abort the import of the other forty-nine.
 */
class GitImportScanTest {

    private final ImportWorkflowDefinitionsFromDirectoryUseCase directoryImport =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class),
                    mock(WorkflowDefinitionRepository.class),
                    new InMemoryImportedDefinitionsRegistry(),
                    new WorkflowDefinitionValidator());

    private final ImportWorkflowDefinitionsFromGitUseCase useCase =
            new ImportWorkflowDefinitionsFromGitUseCase(mock(GitImportProperties.class), directoryImport);

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

    /**
     * The guard. `..` in a configured directory must be refused rather than normalised into a walk
     * out of the clone — the temporary directory this points at is also the one the import deletes
     * afterwards.
     */
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
        assertThatThrownBy(() -> useCase.resolveScanRoot(repo("nope"), root))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not found in repository");
    }

    /**
     * Two entries can name the same repository and different subdirectories. Pruning is scoped by
     * this key, so folding the directory in is what stops one entry's import from concluding that
     * the other's definitions have been removed and archiving them.
     */
    @Test
    void thePruneScopeSeparatesTwoEntriesOnTheSameRepository() {
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo(null)))
                .isEqualTo("https://github.com/org/defs.git");
        assertThat(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo("a")))
                .isNotEqualTo(ImportWorkflowDefinitionsFromGitUseCase.pruneKey(repo("b")));
    }

    @Test
    void onlyDefinitionFilesAreConsidered(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("README.md"), "not a definition");
        Files.writeString(root.resolve("notes.txt"), "nor this");
        var errors = new ArrayList<String>();

        directoryImport.scanAndImport(root, new ArrayList<>(), errors, new HashSet<>());

        // Nothing was a definition, so nothing was imported and nothing failed.
        assertThat(errors).isEmpty();
    }

    /**
     * One unparseable file in a repository must cost that file and nothing else. Letting the
     * exception out would abort the walk, so whichever definitions happened to sort after it would
     * silently stop being imported — and the operator would see a partial import reported as a
     * successful one.
     */
    @Test
    void anUnreadableDefinitionIsReportedAndTheWalkContinues(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("broken.ec"), "{ this is not json");
        Files.writeString(root.resolve("also-broken.json"), "{ nor is this");
        var errors = new ArrayList<String>();
        var imported = new ArrayList<String>();

        directoryImport.scanAndImport(root, imported, errors, new HashSet<>());

        assertThat(errors).hasSize(2);
        assertThat(errors).allSatisfy(error -> assertThat(error).startsWith("File "));
        assertThat(imported).isEmpty();
    }

    /** The error names the file relative to the repository, not by its throwaway temp path. */
    @Test
    void errorsNameTheFileAsItAppearsInTheRepository(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("definitions"));
        Files.writeString(root.resolve("definitions/broken.ec"), "{ this is not json");
        var errors = new ArrayList<String>();

        directoryImport.scanAndImport(root, new ArrayList<>(), errors, new HashSet<>());

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error).contains(root.relativize(root.resolve("definitions/broken.ec")).toString());
            assertThat(error).doesNotContain(root.toString());
        });
    }
}
