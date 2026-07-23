package io.mateu.workflow.application.usecases.lifecycle;

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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelWorkflowDefinitionUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;
    @InjectMocks CancelWorkflowDefinitionUseCase useCase;

    private WorkflowDefinition def(WorkflowDefinitionStatus status) {
        return new WorkflowDefinition("wd-1", "Test", 1, "desc", status, null, false, 0, false, null, List.of());
    }

    @Test
    void archivesTheDefinition() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(def(WorkflowDefinitionStatus.ACTIVE)));
        useCase.handle("wd-1");
        var captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(WorkflowDefinitionStatus.ARCHIVED);
        assertThat(captor.getValue().id()).isEqualTo("wd-1");
    }

    @Test
    void rejectsAlreadyArchived() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(def(WorkflowDefinitionStatus.ARCHIVED)));
        assertThatThrownBy(() -> useCase.handle("wd-1")).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsMissing() {
        when(repository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle("x")).isInstanceOf(IllegalArgumentException.class);
    }
}
