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
class PromoteWorkingCopyUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;

    @InjectMocks PromoteWorkingCopyUseCase useCase;

    private WorkflowDefinition original() {
        return new WorkflowDefinition("wd-1", "My Workflow", 2, "desc",
                WorkflowDefinitionStatus.ACTIVE, null, false, 0, false, List.of());
    }

    private WorkflowDefinition draft(String name) {
        return new WorkflowDefinition("draft-1", name, 2, "new desc",
                WorkflowDefinitionStatus.DRAFT, "wd-1", false, 0, false, List.of());
    }

    @Test
    void promotesWorkingCopyIncrementingVersion() {
        when(repository.findById("draft-1")).thenReturn(Optional.of(draft("My Workflow [draft]")));
        when(repository.findById("wd-1")).thenReturn(Optional.of(original()));
        when(repository.save(any())).thenReturn("wd-1");

        useCase.handle("draft-1");

        ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        WorkflowDefinition promoted = captor.getValue();
        assertThat(promoted.version()).isEqualTo(3);
        assertThat(promoted.id()).isEqualTo("wd-1");
        assertThat(promoted.name()).isEqualTo("My Workflow");
        assertThat(promoted.draftOfId()).isNull();
    }

    @Test
    void deletesDraftAfterPromotion() {
        when(repository.findById("draft-1")).thenReturn(Optional.of(draft("My Workflow [draft]")));
        when(repository.findById("wd-1")).thenReturn(Optional.of(original()));
        when(repository.save(any())).thenReturn("wd-1");

        useCase.handle("draft-1");

        verify(repository).deleteAllById(List.of("draft-1"));
    }

    @Test
    void throwsWhenNotAWorkingCopy() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(original()));

        assertThatThrownBy(() -> useCase.handle("wd-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a working copy");
    }

    @Test
    void throwsWhenWorkingCopyNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void throwsWhenOriginalNotFound() {
        when(repository.findById("draft-1")).thenReturn(Optional.of(draft("My Workflow [draft]")));
        when(repository.findById("wd-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle("draft-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Original");
    }

    @Test
    void nameWithoutDraftSuffixIsKeptAsIs() {
        var draftWithoutSuffix = new WorkflowDefinition("draft-1", "Custom Name", 2, "desc",
                WorkflowDefinitionStatus.DRAFT, "wd-1", false, 0, false, List.of());
        when(repository.findById("draft-1")).thenReturn(Optional.of(draftWithoutSuffix));
        when(repository.findById("wd-1")).thenReturn(Optional.of(original()));
        when(repository.save(any())).thenReturn("wd-1");

        useCase.handle("draft-1");

        ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Custom Name");
    }
}
