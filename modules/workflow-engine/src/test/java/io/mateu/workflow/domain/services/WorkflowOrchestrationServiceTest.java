package io.mateu.workflow.domain.services;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure dataflow semantics of {@link WorkflowOrchestrationService}: a step starts when it is
 * CREATED, ALL its preconditions are COMPLETED and its guard is truthy — nothing else gates
 * it (no array order, no active-step break, {@code parallel} is ignored).
 */
class WorkflowOrchestrationServiceTest {

    private final WorkflowOrchestrationService service = new WorkflowOrchestrationService();

    private Process process() {
        return Process.builder().id("p-1").status(ProcessStatus.RUNNING)
                .variables(List.of()).build();
    }

    private Step step(String id, StepType type, String preconditionStepId, List<String> preconditionStepIds) {
        return new Step(id, "wd-1", type, id, null, preconditionStepId, preconditionStepIds, null, false,
                StepType.ACTION.equals(type) ? "topic" : null, null, null, null, null,
                0, null, null, null, null, 0, 0, false, null, 0);
    }

    private StepExecution se(Step step, StepExecutionStatus status) {
        return StepExecution.builder()
                .id("se-" + step.id()).processId("p-1").workflowDefinitionId("wd-1")
                .stepId(step.id()).stepJson(JsonSerializer.toJson(step))
                .status(status).variables(List.of()).build();
    }

    // ── Dataflow: independent chains never serialize each other ──

    @Test
    void twoIndependentChainsProgressIndependently() {
        // Chain A: a1 (done) → a2 (ready). Chain B: b1 still in flight → b2 waiting.
        var a1 = se(step("a1", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var a2 = se(step("a2", StepType.ACTION, "a1", null), StepExecutionStatus.CREATED);
        var b1 = se(step("b1", StepType.ACTION, "start", null), StepExecutionStatus.PENDING);
        var b2 = se(step("b2", StepType.ACTION, "b1", null), StepExecutionStatus.CREATED);
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);

        var result = service.calculateNextTransitions(process(), List.of(start, a1, a2, b1, b2));

        // a2 starts even though b1 is active — the in-flight step only gates its own successor.
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("a2");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(result.isProcessCompleted()).isFalse();
    }

    @Test
    void allEligibleStepsStartConcurrently() {
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var a = se(step("a", StepType.ACTION, "start", null), StepExecutionStatus.CREATED);
        var b = se(step("b", StepType.ACTION, "start", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, a, b));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(result.getStepsToSave()).allMatch(se -> StepExecutionStatus.PENDING.equals(se.getStatus()));
    }

    // ── JOIN barrier: all plural preconditions must have completed ──

    @Test
    void joinDoesNotStartUntilAllItsPreconditionsComplete() {
        var a = se(step("a", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var b = se(step("b", StepType.ACTION, "start", null), StepExecutionStatus.PENDING);
        var join = se(step("join", StepType.JOIN, null, List.of("a", "b")), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(a, b, join));

        assertThat(result.getStepsToSave()).isEmpty();
        assertThat(result.isProcessCompleted()).isFalse();
    }

    @Test
    void joinCompletesInstantlyOnceAllItsPreconditionsComplete() {
        var a = se(step("a", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var b = se(step("b", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var join = se(step("join", StepType.JOIN, null, List.of("a", "b")), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(a, b, join));

        // JOIN is a pure control-flow node: starting it completes it instantly (no worker).
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("join");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    // ── END transition: only ENDs complete, co-eligible siblings are cancelled ──

    @Test
    void coEligibleNonEndSiblingIsCancelledNotCompletedWhenAnEndFires() {
        // Both "end" and "action" become eligible in the same transition. The ACTION never
        // ran, so it must come out CANCELLED — never COMPLETED, never started.
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var action = se(step("action", StepType.ACTION, "start", null), StepExecutionStatus.CREATED);
        var end = se(step("end", StepType.END, "start", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, action, end));

        var byStepId = result.getStepsToSave().stream()
                .collect(java.util.stream.Collectors.toMap(StepExecution::getStepId, StepExecution::getStatus));
        assertThat(byStepId).containsEntry("end", StepExecutionStatus.COMPLETED);
        assertThat(byStepId).containsEntry("action", StepExecutionStatus.CANCELLED);
        assertThat(result.isProcessCompleted()).isTrue();
        assertThat(result.getUpdatedProcess().getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    @Test
    void endTransitionStillCancelsInFlightStepsAndCompletesTheEnd() {
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var inFlight = se(step("busy", StepType.ACTION, "start", null), StepExecutionStatus.PENDING);
        var end = se(step("end", StepType.END, "start", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, inFlight, end));

        var byStepId = result.getStepsToSave().stream()
                .collect(java.util.stream.Collectors.toMap(StepExecution::getStepId, StepExecution::getStatus));
        assertThat(byStepId).containsEntry("end", StepExecutionStatus.COMPLETED);
        assertThat(byStepId).containsEntry("busy", StepExecutionStatus.CANCELLED);
        assertThat(result.isProcessCompleted()).isTrue();
    }

    // ── START / FORK: instant pass-through control-flow nodes ──

    @Test
    void startStepCompletesInstantlyAtProcessCreation() {
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.CREATED);
        var next = se(step("next", StepType.ACTION, "start", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, next));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("start");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void completedForkFansOutToAllItsSuccessors() {
        var fork = se(step("fork", StepType.FORK, "start", null), StepExecutionStatus.COMPLETED);
        var branch1 = se(step("branch1", StepType.ACTION, "fork", null), StepExecutionStatus.CREATED);
        var branch2 = se(step("branch2", StepType.ACTION, "fork", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(fork, branch1, branch2));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId)
                .containsExactlyInAnyOrder("branch1", "branch2");
    }

    // ── PAUSED: the gate holds everything until resume ──

    @Test
    void pausedProcessStartsNothingEvenWithEligibleSteps() {
        var pausedProcess = Process.builder().id("p-1").status(ProcessStatus.PAUSED)
                .variables(List.of()).build();
        var done = se(step("a1", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var eligible = se(step("a2", StepType.ACTION, "a1", null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(pausedProcess, List.of(done, eligible));

        assertThat(result.getStepsToSave()).isEmpty();
        assertThat(result.getUpdatedProcess()).isSameAs(pausedProcess);
        assertThat(result.isProcessCompleted()).isFalse();
        assertThat(result.isProcessErrored()).isFalse();
    }

    @Test
    void pausedProcessDefersBlockingErrorHandlingUntilResume() {
        var pausedProcess = Process.builder().id("p-1").status(ProcessStatus.PAUSED)
                .variables(List.of()).build();
        var failed = se(step("a1", StepType.ACTION, "start", null), StepExecutionStatus.ERROR);

        var result = service.calculateNextTransitions(pausedProcess, List.of(failed));

        // The failed step must not flip the process to ERROR while it is paused.
        assertThat(result.getUpdatedProcess().getStatus()).isEqualTo(ProcessStatus.PAUSED);
        assertThat(result.isProcessErrored()).isFalse();
        assertThat(result.getStepsToSave()).isEmpty();
    }
}
