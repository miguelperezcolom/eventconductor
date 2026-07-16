package io.mateu.workflow.application.usecases.completetask;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.dtos.events.integration.TaskStatusChanged;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompleteTaskUseCaseTest {

    @Mock FormExecutionRepository formExecutionRepository;
    @Mock StreamBridge streamBridge;

    @InjectMocks CompleteTaskUseCase useCase;

    private FormExecution formExecution(String id) {
        return FormExecution.builder()
                .id(id).formId("f-1").processId("p-1").stepExecutionId("se-1")
                .status(FormExecutionStatus.PENDING).userId("alice")
                .variables(List.of()).values(List.of()).build();
    }

    @Test
    void completesTheTaskWithTheSubmittedValues() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));

        useCase.handle(new CompleteTaskCommand("fe-1", List.of(new Value("name", "John"))));

        ArgumentCaptor<FormExecution> captor = ArgumentCaptor.forClass(FormExecution.class);
        verify(formExecutionRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(FormExecutionStatus.COMPLETED);
        assertThat(captor.getValue().values()).containsExactly(new Value("name", "John"));
    }

    @Test
    void emitsTaskStatusChangedWithTheValuesAsVariables() {
        when(formExecutionRepository.findById("fe-1")).thenReturn(Optional.of(formExecution("fe-1")));

        useCase.handle(new CompleteTaskCommand("fe-1", List.of(new Value("name", "John"))));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(streamBridge, times(2)).send(eq("upstream"), captor.capture());
        var statusChanged = captor.getAllValues().stream()
                .filter(TaskStatusChanged.class::isInstance)
                .map(TaskStatusChanged.class::cast)
                .findFirst().orElseThrow();
        assertThat(statusChanged.taskExecutionId()).isEqualTo("se-1");
        assertThat(statusChanged.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(statusChanged.variables()).hasSize(1);
        assertThat(statusChanged.variables().getFirst().name()).isEqualTo("name");
        assertThat(statusChanged.variables().getFirst().value()).isEqualTo("John");
    }

    @Test
    void failsWhenTheTaskDoesNotExist() {
        when(formExecutionRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(new CompleteTaskCommand("missing", List.of())))
                .isInstanceOf(Exception.class);
        verify(formExecutionRepository, never()).save(any());
        verify(streamBridge, never()).send(any(), any());
    }
}
