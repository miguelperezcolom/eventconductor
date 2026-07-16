package io.mateu.workflow.application.usecases.stepexecution.start;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.DownstreamEventPublisher;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
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
class StartStepExecutionUseCaseTest {

    @Mock StepExecutionRepository stepExecutionRepository;
    @Mock DownstreamEventPublisher downstreamEventPublisher;

    @InjectMocks StartStepExecutionUseCase useCase;

    private StepExecution seWith(StepExecutionStatus status, StepType type, String formId) {
        return seWith(status, type, formId, null);
    }

    private StepExecution seWith(StepExecutionStatus status, StepType type, String formId, String ruleId) {
        Step step = new Step("s1", "wd-1", type, "Step", null, null, null, false, "topic", formId, ruleId, null, 0, null, null, null, 0, 0, false, null);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .status(status).variables(List.of()).build();
    }

    @Test
    void dispatchesTaskExecutionRequestedWhenPending() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<TaskExecutionRequested> captor = ArgumentCaptor.forClass(TaskExecutionRequested.class);
        verify(downstreamEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().taskId()).isEmpty();
    }

    @Test
    void setsCompleteFormTaskIdForUserTaskStep() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.USER_TASK, "form-1");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<TaskExecutionRequested> captor = ArgumentCaptor.forClass(TaskExecutionRequested.class);
        verify(downstreamEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo("complete-form");
    }

    @Test
    void setsEvaluateRuleTaskIdForRuleStep() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.RULE, null, "rule-1");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<TaskExecutionRequested> captor = ArgumentCaptor.forClass(TaskExecutionRequested.class);
        verify(downstreamEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().taskId()).isEqualTo("evaluate-rule");
    }

    @Test
    void ignoresDuplicateWhenNotPending() {
        var se = seWith(StepExecutionStatus.RUNNING, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        verify(downstreamEventPublisher, never()).publish(any());
    }

    @Test
    void ignoresDuplicateWhenCompleted() {
        var se = seWith(StepExecutionStatus.COMPLETED, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        verify(downstreamEventPublisher, never()).publish(any());
    }
}
