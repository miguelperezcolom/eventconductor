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

    // The real no-op, not a mock: a mocked span() would swallow the work it is meant to wrap.
    @org.mockito.Spy
    io.mateu.workflow.application.out.WorkflowTracing workflowTracing =
            io.mateu.workflow.application.out.WorkflowTracing.NOOP;

    // The real one, not a mock: the anchor it computes is what puts this work in its process's
    // trace, and a mock would hand back null and quietly take that away. Everything traced, so the
    // sampling decision is never what a test here turns on.
    @org.mockito.Spy
    io.mateu.workflow.application.services.ProcessTrace processTrace =
            new io.mateu.workflow.application.services.ProcessTrace(1.0);

    @InjectMocks StartStepExecutionUseCase useCase;

    private StepExecution seWith(StepExecutionStatus status, StepType type, String formId) {
        return seWith(status, type, formId, null);
    }

    private StepExecution seWith(StepExecutionStatus status, StepType type, String formId, String ruleId) {
        Step step = new Step("s1", "wd-1", type, "Step", null, null, null, null, false, "topic", formId, ruleId, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
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
        verify(downstreamEventPublisher).publish(captor.capture(), any());
        assertThat(captor.getValue().taskId()).isEmpty();
    }

    /**
     * The dispatch carries the step's destination, and the step frozen on the execution is where it
     * comes from. Asserted separately from the event itself because the topic is not in the event:
     * it is how the event is addressed, and a wrong address fails silently — the send succeeds and
     * only the worker's silence, later, says anything went wrong.
     */
    @Test
    void dispatchesToTheTopicTheStepNames() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(downstreamEventPublisher).publish(any(), topic.capture());
        assertThat(topic.getValue()).isEqualTo("topic");
    }

    @Test
    void dispatchesWithNoTopicWhenTheStepNamesNone() {
        Step step = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false, null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0, null);
        var se = StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING).variables(List.of()).build();
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        verify(downstreamEventPublisher).publish(any(), topic.capture());
        // Null rather than "downstream": resolving the default belongs to the publisher, so an
        // embedded run is not handed a Kafka binding name it has no use for.
        assertThat(topic.getValue()).isNull();
    }

    @Test
    void setsCompleteFormTaskIdForUserTaskStep() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.USER_TASK, "form-1");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<TaskExecutionRequested> captor = ArgumentCaptor.forClass(TaskExecutionRequested.class);
        verify(downstreamEventPublisher).publish(captor.capture(), any());
        assertThat(captor.getValue().taskId()).isEqualTo("complete-form");
    }

    @Test
    void setsEvaluateRuleTaskIdForRuleStep() {
        var se = seWith(StepExecutionStatus.PENDING, StepType.RULE, null, "rule-1");
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        ArgumentCaptor<TaskExecutionRequested> captor = ArgumentCaptor.forClass(TaskExecutionRequested.class);
        verify(downstreamEventPublisher).publish(captor.capture(), any());
        assertThat(captor.getValue().taskId()).isEqualTo("evaluate-rule");
    }

    @Test
    void ignoresDuplicateWhenNotPending() {
        var se = seWith(StepExecutionStatus.RUNNING, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        verify(downstreamEventPublisher, never()).publish(any(), any());
    }

    @Test
    void ignoresDuplicateWhenCompleted() {
        var se = seWith(StepExecutionStatus.COMPLETED, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        verify(downstreamEventPublisher, never()).publish(any(), any());
    }

    /** A step marked started some time ago and only now reaching a worker — a queued dispatch. */
    private StepExecution queuedSince(java.time.LocalDateTime startedAt, long timeoutMillis) {
        Step step = new Step("s1", "wd-1", StepType.ACTION, "Step", null, null, null, null, false,
                "topic", null, null, null, null, 0, null, null, null, null, timeoutMillis, 0, false,
                null, 0, null);
        return StepExecution.builder()
                .id("se-1").processId("p-1").workflowDefinitionId("wd-1").stepId("s1")
                .stepJson(JsonSerializer.toJson(step))
                .status(StepExecutionStatus.PENDING).variables(List.of())
                .startedAt(startedAt)
                .build();
    }

    /**
     * The step's clock starts when the task actually leaves for a worker, not when the orchestrator
     * wrote its dispatch to the outbox.
     *
     * <p>Charging the queue to the step is how a backlog becomes failures rather than latency: once
     * the dispatch backlog exceeds the timeout, steps expire before any worker has seen them, their
     * retries expire with them, and their sagas roll back. Under deliberate overload that produced
     * 12,517 ERROR and 3,035 COMPENSATION_FAILED, none of it a worker doing anything wrong.
     *
     * <p>Here the step was marked started ten minutes ago and carries a thirty-second timeout — so
     * on the old reckoning it is already long expired at the moment it is handed over.
     */
    @Test
    void theTimeoutIsMeasuredFromTheDispatchAndNotFromTheQueueing() {
        var markedStartedTenMinutesAgo = java.time.LocalDateTime.now().minusMinutes(10);
        var se = queuedSince(markedStartedTenMinutesAgo, 30_000);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        var saved = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(saved.capture());
        assertThat(saved.getValue().getStartedAt()).isAfter(markedStartedTenMinutesAgo);
        // The whole thirty seconds are still ahead of it, so the timeout scan cannot claim it.
        assertThat(saved.getValue().getDeadlineAt()).isAfter(java.time.LocalDateTime.now());
    }

    /** Re-arming must not invent a deadline for a step that asked for none. */
    @Test
    void aStepWithoutATimeoutStillHasNoDeadline() {
        var se = queuedSince(java.time.LocalDateTime.now().minusMinutes(10), 0);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        var saved = ArgumentCaptor.forClass(StepExecution.class);
        verify(stepExecutionRepository).save(saved.capture());
        assertThat(saved.getValue().getDeadlineAt()).isNull();
    }

    /** A step that is not dispatched is not re-armed either — the guard runs first. */
    @Test
    void aDuplicateArrivingAfterTheWorkerRespondedDoesNotExtendTheDeadline() {
        var se = seWith(StepExecutionStatus.RUNNING, StepType.ACTION, null);
        when(stepExecutionRepository.findById("se-1")).thenReturn(Optional.of(se));

        useCase.handle(new StartStepExecutionCommand("se-1"));

        verify(stepExecutionRepository, never()).save(any());
    }
}
