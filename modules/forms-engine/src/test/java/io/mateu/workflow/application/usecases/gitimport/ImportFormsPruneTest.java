package io.mateu.workflow.application.usecases.gitimport;

import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.infra.config.GitImportProperties;
import io.mateu.workflow.webhook.InMemoryImportedDefinitionsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImportFormsPruneTest {

    private final FormRepository repository = mock(FormRepository.class);
    private final InMemoryImportedDefinitionsRegistry registry = new InMemoryImportedDefinitionsRegistry();
    private final ImportFormsFromGitUseCase useCase = new ImportFormsFromGitUseCase(
            mock(GitImportProperties.class), repository, mock(FormsMetrics.class), registry);

    private static final String REPO = "https://github.com/org/forms.git";

    @Test
    void deletesFormsRemovedFromTheRepo() {
        registry.replace("form", REPO, Set.of("a", "b"));
        when(repository.findById("b")).thenReturn(Optional.of(new Form("b", "B", null, List.of())));

        var pruned = new ArrayList<String>();
        useCase.pruneRemovedForms(REPO, Set.of("a"), pruned); // "b" is gone

        verify(repository).deleteAllById(List.of("b"));
        assertThat(pruned).hasSize(1);
        assertThat(registry.idsFor("form", REPO)).containsExactly("a");
    }

    @Test
    void doesNothingWhenEverythingStillPresent() {
        registry.replace("form", REPO, Set.of("a", "b"));
        var pruned = new ArrayList<String>();
        useCase.pruneRemovedForms(REPO, Set.of("a", "b"), pruned);
        verify(repository, never()).deleteAllById(any());
        assertThat(pruned).isEmpty();
    }
}
