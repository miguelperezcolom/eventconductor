package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ImportWorkflowDefinitionsPruneTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportWorkflowDefinitionsFromGitUseCase useCase =
            new ImportWorkflowDefinitionsFromGitUseCase(
                    mock(GitImportProperties.class), repository, mock(WorkflowDefinitionValidator.class), registry);

    private static final String REPO = "https://github.com/org/defs.git";

    private static WorkflowDefinition def(String id, WorkflowDefinitionStatus status) {
        return new WorkflowDefinition(id, id.toUpperCase(), 1, null, status, null,
                false, 0, false, null, 0, List.of());
    }

    @Test
    void archivesDefinitionsRemovedFromTheRepo() {
        registry.replace("workflow", REPO, Set.of("a", "b"));
        when(repository.findById("b")).thenReturn(Optional.of(def("b", WorkflowDefinitionStatus.ACTIVE)));

        var pruned = new ArrayList<String>();
        useCase.pruneRemovedDefinitions(REPO, Set.of("a"), pruned); // "b" is gone now

        // Archived directly (bypasses the ACTIVE→DISABLED→ARCHIVED lifecycle guards, on purpose).
        verify(repository).save(argThat(d -> d.id().equals("b")
                && d.status() == WorkflowDefinitionStatus.ARCHIVED));
        assertThat(pruned).hasSize(1);
        // Registry now reflects the latest import.
        assertThat(registry.idsFor("workflow", REPO)).containsExactly("a");
    }

    @Test
    void doesNothingWhenEverythingStillPresent() {
        registry.replace("workflow", REPO, Set.of("a", "b"));

        var pruned = new ArrayList<String>();
        useCase.pruneRemovedDefinitions(REPO, Set.of("a", "b"), pruned);

        verify(repository, never()).save(any());
        assertThat(pruned).isEmpty();
    }

    @Test
    void skipsDefinitionsThatAreAlreadyArchived() {
        registry.replace("workflow", REPO, Set.of("a", "b"));
        when(repository.findById("b")).thenReturn(Optional.of(def("b", WorkflowDefinitionStatus.ARCHIVED)));

        var pruned = new ArrayList<String>();
        useCase.pruneRemovedDefinitions(REPO, Set.of("a"), pruned);

        verify(repository, never()).save(any());
        assertThat(pruned).isEmpty();
    }
}
