package io.mateu.workflow.application.usecases.stepexecution.update;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegisterLogMessageUseCaseTest {

    @Mock StepExecutionRepository repository;
    @Mock LogMessageRepository logMessageRepository;
    @Mock ProcessRepository processRepository;

    @InjectMocks RegisterLogMessageUseCase useCase;

    @Test
    void savesLogMessageWithCorrectFields() {
        var se = StepExecution.builder()
                .id("se-1").processId("p-1")
                .status(StepExecutionStatus.PENDING).build();
        when(repository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new RegisterLogMessageCommand("se-1", MessageType.Error, "Something failed"));

        ArgumentCaptor<LogMessage> captor = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(captor.capture());
        LogMessage saved = captor.getValue();
        assertThat(saved.getProcessId()).isEqualTo("p-1");
        assertThat(saved.getStepExecutionId()).isEqualTo("se-1");
        assertThat(saved.getMessageType()).isEqualTo(MessageType.Error.name());
        assertThat(saved.getMessage()).isEqualTo("Something failed");
    }
}
