package io.mateu.workflow.application.usecases.lifecycle;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
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
class DisableWorkflowDefinitionUseCaseTest {

    @Mock WorkflowDefinitionRepository repository;
    @InjectMocks DisableWorkflowDefinitionUseCase useCase;

    private WorkflowDefinition def(boolean disabled) {
        return new WorkflowDefinition("wd-1", "Test", 1, "desc", false, 0, false, null, 0,
                List.of(), false, disabled, false);
    }

    @Test
    void disablesAnEnabledDefinition() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(def(false)));
        useCase.handle("wd-1");
        var captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().disabled()).isTrue();
    }

    @Test
    void idempotentWhenAlreadyDisabled() {
        when(repository.findById("wd-1")).thenReturn(Optional.of(def(true)));
        useCase.handle("wd-1");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsMissing() {
        when(repository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle("x")).isInstanceOf(IllegalArgumentException.class);
    }
}
