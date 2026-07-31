package io.mateu.workflow.application.usecases.process.pause;

import io.mateu.workflow.application.out.LogMessageRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PauseProcessUseCaseTest {

    @Mock ProcessRepository processRepository;
    @Mock LogMessageRepository logMessageRepository;

    @InjectMocks PauseProcessUseCase useCase;

    private Process process(ProcessStatus status) {
        return Process.builder().id("p-1").status(status).build();
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class, names = {"PENDING", "RUNNING"})
    void pausesPendingOrRunningProcess(ProcessStatus from) {
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process(from)));

        useCase.handle(new PauseProcessCommand("p-1"));

        var captor = ArgumentCaptor.forClass(Process.class);
        verify(processRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(captor.getValue().getPausedAt()).isNotNull();

        var logCaptor = ArgumentCaptor.forClass(LogMessage.class);
        verify(logMessageRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getMessage()).isEqualTo("Process paused");
        assertThat(logCaptor.getValue().getProcessId()).isEqualTo("p-1");
    }

    @ParameterizedTest
    @EnumSource(value = ProcessStatus.class, names = {"PAUSED", "COMPLETED", "CANCELLED", "ERROR"})
    void doesNothingFromAnyOtherStatus(ProcessStatus from) {
        when(processRepository.findById("p-1")).thenReturn(Optional.of(process(from)));

        useCase.handle(new PauseProcessCommand("p-1"));

        verify(processRepository, never()).save(any());
        verify(logMessageRepository, never()).save(any());
    }
}
