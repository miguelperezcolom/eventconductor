package io.mateu.workflow.application.usecases.workingcopy;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowDefinitionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateWorkingCopyUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;

    @InjectMocks CreateWorkingCopyUseCase useCase;

    private WorkflowDefinition activeWorkflow() {
        return new WorkflowDefinition("wd-1", "My Workflow", 1, "desc",
                WorkflowDefinitionStatus.ACTIVE, null, false, 0, false, null, 0, List.of());
    }

    @Test
    void createsWorkingCopyWithDraftStatus() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(activeWorkflow()));
        when(repository.findAll()).thenReturn(List.of(activeWorkflow()));
        when(repository.save(any())).thenReturn("new-id");

        useCase.handle("wd-1");

        ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        WorkflowDefinition draft = captor.getValue();
        assertThat(draft.status()).isEqualTo(WorkflowDefinitionStatus.DRAFT);
        assertThat(draft.draftOfId()).isEqualTo("wd-1");
        assertThat(draft.name()).contains("[draft]");
    }

    @Test
    void throwsWhenWorkflowNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenWorkingCopyAlreadyExists() {
        var original = activeWorkflow();
        var existingDraft = new WorkflowDefinition("draft-1", "My Workflow [draft]", 1, "desc",
                WorkflowDefinitionStatus.DRAFT, "wd-1", false, 0, false, null, 0, List.of());

        when(repository.findById("wd-1")).thenReturn(Optional.of(original));
        when(repository.findAll()).thenReturn(List.of(original, existingDraft));

        assertThatThrownBy(() -> useCase.handle("wd-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("working copy");
    }
}
