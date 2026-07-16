package io.mateu.workflow.infra.in.async.processdomainevent.domaineventhandlers;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.usecases.process.stepover.StepOverProcessUseCase;
import io.mateu.workflow.application.usecases.process.update.ProcessUpdateStepExecutionUpdateUseCase;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskCancellationRequested;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
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
class StepExecutionStatusUpdatedEventHandlerTest {

    @Mock ProcessUpdateStepExecutionUpdateUseCase processUpdateUseCase;
    @Mock StepOverProcessUseCase stepOverProcessUseCase;
    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock ProcessRepository processRepository;
    @Mock DownstreamEventPublisher downstreamEventPublisher;
    @Mock WorkflowMetrics workflowMetrics;

    @InjectMocks StepExecutionStatusUpdatedEventHandler handler;

    private Step step(int retries, boolean rollbackable, String compensationStepId) {
        return new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, false, "topic",
                null, null, null, 0, null, null, null, 0, retries, rollbackable, compensationStepId);
    }

    private StepExecution se(int attemptCount, int retries, boolean rollbackable, String compensationStepId) {
        Step step = step(retries, rollbackable, compensationStepId);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .attemptCount(attemptCount)
                .status(StepExecutionStatus.ERROR)
                .variables(List.of()).build();
    }

    @Test
    void canHandleStepExecutionStatusChanged() {
        org.assertj.core.api.Assertions.assertThat(handler.eventClass()).isEqualTo(StepExecutionStatusChanged.class);
    }

    @Test
    void retriesWhenAttemptsRemaining() {
        var se = se(0, 2, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        verify(stepExecutionRepository).save(se);
        verify(stepOverProcessUseCase).handle(any());
        verify(processUpdateUseCase, never()).handle(any());
        verify(workflowMetrics).retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.AUTO);
    }

    @Test
    void updatesProcessWhenRetriesExhausted() {
        var se = se(3, 3, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        verify(processUpdateUseCase).handle(any());
        verify(stepOverProcessUseCase).handle(any());
    }

    @Test
    void cancelsWorkerOnTimeout() {
        var se = se(3, 3, false, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.TIMEOUT, List.of()));

        verify(downstreamEventPublisher).publish(any(TaskCancellationRequested.class));
    }

    @Test
    void triggersCompensationWhenRollbackable() {
        var se = se(1, 1, true, "comp-step");
        var compensationStep = step(0, false, null).withId("comp-step");
        var compensationSe = StepExecution.builder()
                .id("comp-se").processId("p-1").workflowDefinitionId("wd-1").stepId("comp-step")
                .stepJson(JsonSerializer.toJson(compensationStep))
                .status(StepExecutionStatus.CREATED)
                .variables(List.of()).build();
        var proc = Process.builder().id("p-1").variables(List.of()).build();

        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));
        when(processRepository.findById("p-1")).thenReturn(Optional.of(proc));
        when(stepExecutionRepository.findByProcess(proc)).thenReturn(List.of(se, compensationSe));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.ERROR, List.of()));

        verify(stepExecutionRepository).save(compensationSe);
        verify(workflowMetrics).compensationTriggered("wd-1");
    }

    @Test
    void updatesProcessWhenCompleted() {
        Step step = step(0, false, null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .attemptCount(0)
                .status(StepExecutionStatus.COMPLETED)
                .variables(List.of()).build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        handler.handle(new StepExecutionStatusChanged("se-1", TaskStatus.COMPLETED, List.of()));

        verify(processUpdateUseCase).handle(any());
        verify(stepOverProcessUseCase).handle(any());
    }
}
