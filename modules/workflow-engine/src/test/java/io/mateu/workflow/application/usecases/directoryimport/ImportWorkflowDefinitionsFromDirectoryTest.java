package io.mateu.workflow.application.usecases.directoryimport;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.config.DirectoryImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Importing definitions from a directory on disk — the thing a mounted volume, a checkout beside
 * the app, or anything that writes definitions needs, and which until now could only be had by
 * committing them and letting the engine clone.
 */
class ImportWorkflowDefinitionsFromDirectoryTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportWorkflowDefinitionsFromDirectoryUseCase useCase =
            new ImportWorkflowDefinitionsFromDirectoryUseCase(
                    mock(DirectoryImportProperties.class), repository, registry);

    private void write(Path dir, String name, String id) throws IOException {
        Files.writeString(dir.resolve(name), """
                id: %s
                name: %s
                version: 1
                steps:
                  - id: start
                    type: START
                    name: Start
                  - id: end
                    type: END
                    name: End
                    preconditionStepId: start
                """.formatted(id, id));
    }

    private List<WorkflowDefinition> saved() {
        var captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void importsEveryDefinitionUnderTheDirectory(@TempDir Path dir) throws Exception {
        write(dir, "one.yml.ec", "one");
        Files.createDirectories(dir.resolve("nested"));
        write(dir.resolve("nested"), "two.yml.ec", "two");
        Files.writeString(dir.resolve("README.md"), "not a definition");

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(result.errors()).isEmpty();
        assertThat(saved()).extracting(WorkflowDefinition::id).containsExactlyInAnyOrder("one", "two");
        assertThat(result.imported()).hasSize(2);
    }

    @Test
    void aDirectoryThatIsNotThereIsAnErrorRatherThanASilence(@TempDir Path dir) {
        // The failure mode this exists to avoid: a typo in the mount path, an engine that starts
        // clean and a definition list that is empty for no stated reason.
        var result = useCase.handle(List.of(dir.resolve("nope").toString()));

        assertThat(result.imported()).isEmpty();
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error).startsWith("Directory ");
            assertThat(error).contains("not a directory");
        });
    }

    @Test
    void aDefinitionThatLeavesTheDirectoryIsPrunedOnTheNextImport(@TempDir Path dir) throws Exception {
        write(dir, "one.yml.ec", "one");
        write(dir, "two.yml.ec", "two");
        useCase.handle(List.of(dir.toString()));
        assertThat(registry.idsFor("workflow", dir.toRealPath().toString())).containsExactlyInAnyOrder("one", "two");

        Files.delete(dir.resolve("two.yml.ec"));
        var gone = new WorkflowDefinition("two", "two", 1, null, false, 0, false, null, 0, List.of());
        when(repository.findById("two")).thenReturn(Optional.of(gone));

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(result.pruned()).singleElement().satisfies(p -> assertThat(p).contains("two"));
        verify(repository).save(argThat(d -> "two".equals(d.id()) && d.archived()));
    }

    @Test
    void oneBadFileCostsThatFileAndNothingElse(@TempDir Path dir) throws Exception {
        write(dir, "good.yml.ec", "good");
        Files.writeString(dir.resolve("broken.ec"), "{ this is not json");

        var result = useCase.handle(List.of(dir.toString()));

        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e).contains("broken.ec"));
        assertThat(saved()).extracting(WorkflowDefinition::id).containsExactly("good");
    }
}
