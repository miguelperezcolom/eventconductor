package io.mateu.workflow.application.usecases.process.retry;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessCommand;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryProcessUseCaseTest {

    @Mock
    ProcessRepository processRepository;
    @Mock
    StepExecutionRepository stepExecutionRepository;
    @Mock
    StepOverProcessUseCase stepOverProcessUseCase;
    @Mock
    WorkflowMetrics workflowMetrics;

    @InjectMocks
    RetryProcessUseCase retryProcessUseCase;

    @Test
    void shouldRetryProcess() {
        // given
        String processId = "process-1";
        Process process = Process.builder()
                .id(processId)
                .status(ProcessStatus.ERROR)
                .build();
        
        StepExecution stepExecution = StepExecution.builder()
                .id("se-1")
                .status(StepExecutionStatus.ERROR)
                .build();

        when(processRepository.findById(processId)).thenReturn(Optional.of(process));
        when(stepExecutionRepository.findByProcess(any())).thenReturn(List.of(stepExecution));

        // when
        retryProcessUseCase.handle(new RetryProcessCommand(processId));

        // then
        verify(stepExecutionRepository).save(any(StepExecution.class));
        verify(processRepository).save(any(Process.class));
        verify(stepOverProcessUseCase).handle(any(StepOverProcessCommand.class));
        verify(workflowMetrics).retryPerformed(any(), eq(WorkflowMetrics.RetryTrigger.MANUAL));
    }
}
