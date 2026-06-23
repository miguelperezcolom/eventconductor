package io.mateu.workflow.application.usecases.canceltask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntity;
import io.mateu.workflow.infra.out.persistence.FormExecutionEntityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelTaskUseCaseTest {

    @Mock FormExecutionRepository formExecutionRepository;
    @Mock FormExecutionEntityRepository formExecutionEntityRepository;

    @InjectMocks CancelTaskUseCase useCase;

    private FormExecutionEntity entity(String id, String stepExecutionId) {
        var e = new FormExecutionEntity();
        e.setId(id);
        e.setStepExecutionId(stepExecutionId);
        e.setStatus("PENDING");
        return e;
    }

    private FormExecution formExecution(String id) {
        return FormExecution.builder()
                .id(id).formId("f-1").processId("p-1")
                .status(FormExecutionStatus.PENDING)
                .variables(List.of()).values(List.of()).build();
    }

    @Test
    void cancelsPendingFormExecutions() {
        var entity = entity("fe-1", "se-1");
        var fe = formExecution("fe-1");
        when(formExecutionEntityRepository.findByStepExecutionId("se-1")).thenReturn(List.of(entity));
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(fe));

        useCase.handle(new CancelTaskCommand("se-1"));

        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(FormExecutionStatus.CANCELLED);
    }

    @Test
    void doesNothingWhenNoFormExecutionsFound() {
        when(formExecutionEntityRepository.findByStepExecutionId("se-1")).thenReturn(List.of());

        useCase.handle(new CancelTaskCommand("se-1"));

        verify(formExecutionRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenFormExecutionNotInRepository() {
        var entity = entity("fe-1", "se-1");
        when(formExecutionEntityRepository.findByStepExecutionId("se-1")).thenReturn(List.of(entity));
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.empty());

        useCase.handle(new CancelTaskCommand("se-1"));

        verify(formExecutionRepository, never()).save(any());
    }
}
