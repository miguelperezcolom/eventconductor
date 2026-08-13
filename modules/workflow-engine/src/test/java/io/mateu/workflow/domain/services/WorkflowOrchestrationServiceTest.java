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
                0, null, null, null, null, 0, 0, false, null, 0, null);
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

    @Test
    void xorJoinStartsAsSoonAsAnyPreconditionCompletes() {
        var a = se(step("a", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var b = se(step("b", StepType.ACTION, "start", null), StepExecutionStatus.PENDING); // still in flight
        var join = se(xorJoin("join", List.of("a", "b")), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(a, b, join));

        // XOR join proceeds on the first completed branch, unlike the AND join above.
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("join");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    private Step xorJoin(String id, List<String> preconditionStepIds) {
        return new Step(id, "wd-1", StepType.JOIN, id, null, null, preconditionStepIds, null, false,
                null, null, null, null, null, 0, null, null, null, null, 0, 0, false, null, 0,
                io.mateu.workflow.domain.aggregates.JoinType.XOR);
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

    // ── Nothing to wait for is not permission to run ──

    @Test
    void aStepWithNoPreconditionsDoesNotRunUnlessItIsAnEntryPoint() {
        // 'refund' compensates 'charge'. It declares no way in because it needs none: the
        // rollback pipeline starts it. Read as "no preconditions, so all of them are satisfied",
        // it would instead run the moment the process began.
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var charge = se(step("charge", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var next = se(step("next", StepType.ACTION, "charge", null), StepExecutionStatus.CREATED);
        var refund = se(step("refund", StepType.ACTION, null, null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, charge, next, refund));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId)
                .containsExactly("next");
    }

    @Test
    void aCompensationNeverNeededIsCancelledWithEverythingElseWhenTheFlowRunsOut() {
        // And it does not hold the process open either: it is not waiting for anything, it is
        // simply not part of this run.
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var charge = se(step("charge", StepType.ACTION, "start", null), StepExecutionStatus.COMPLETED);
        var refund = se(step("refund", StepType.ACTION, null, null), StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(process(), List.of(start, charge, refund));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("refund");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(result.isProcessCompleted()).isTrue();
    }

    // ── CHOICE: exclusive split, longest satisfied guard first, unguarded default last ──

    private Process processWith(String... nameThenValue) {
        var vars = new java.util.ArrayList<io.mateu.workflow.domain.aggregates.Variable>();
        for (int i = 0; i < nameThenValue.length; i += 2) {
            vars.add(new io.mateu.workflow.domain.aggregates.Variable(
                    nameThenValue[i], nameThenValue[i + 1]));
        }
        return Process.builder().id("p-1").status(ProcessStatus.RUNNING).variables(vars).build();
    }

    /** A successor reached from a CHOICE by a link carrying {@code guard} (null = the default branch). */
    private StepExecution choiceSuccessor(String id, String choiceId, String guard, StepExecutionStatus status) {
        var step = step(id, StepType.ACTION, null, null)
                .withPreconditions(List.of(new io.mateu.workflow.domain.aggregates.Precondition(choiceId, guard)));
        return se(step, status);
    }

    @Test
    void choiceTakesTheSuccessorWithTheLongestSatisfiedGuard() {
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.COMPLETED);
        var specific = choiceSuccessor("specific", "choice", "status == 'vip' && tier == 'gold'", StepExecutionStatus.CREATED);
        var general = choiceSuccessor("general", "choice", "status == 'vip'", StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(
                processWith("status", "vip", "tier", "gold"), List.of(choice, specific, general));

        // Both guards hold, but the longer (more specific) one is evaluated first and wins — and it
        // is the ONLY branch taken.
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("specific");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }

    @Test
    void choiceFallsBackToTheUnguardedDefaultWhenNoGuardHolds() {
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.COMPLETED);
        var guarded = choiceSuccessor("guarded", "choice", "status == 'vip'", StepExecutionStatus.CREATED);
        var fallback = choiceSuccessor("fallback", "choice", null, StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(processWith("status", "regular"), List.of(choice, guarded, fallback));

        // The guarded branch is false; the unguarded branch is the else, tried last, and taken.
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("fallback");
    }

    @Test
    void choiceWithNoMatchingGuardAndNoDefaultTakesNoBranchAndLetsTheProcessComplete() {
        var start = se(step("start", StepType.START, null, null), StepExecutionStatus.COMPLETED);
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.COMPLETED);
        var only = choiceSuccessor("only", "choice", "status == 'vip'", StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(processWith("status", "regular"), List.of(start, choice, only));

        // No guard holds and there is no default: the split takes nothing. The discarded successor
        // is not "held waiting for its guard", so the process completes and cancels it.
        var byStepId = result.getStepsToSave().stream()
                .collect(java.util.stream.Collectors.toMap(StepExecution::getStepId, StepExecution::getStatus));
        assertThat(byStepId).containsEntry("only", StepExecutionStatus.CANCELLED);
        assertThat(result.isProcessCompleted()).isTrue();
    }

    @Test
    void choicePickLatchesOnceASiblingHasLeftTheStartingGate() {
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.COMPLETED);
        // 'general' already started on an earlier cycle; 'specific' only now carries the longer guard.
        var general = choiceSuccessor("general", "choice", "status == 'vip'", StepExecutionStatus.PENDING);
        var specific = choiceSuccessor("specific", "choice", "status == 'vip' && tier == 'gold'", StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(
                processWith("status", "vip", "tier", "gold"), List.of(choice, general, specific));

        // The pick latches: even though 'specific' would now win the length race, a sibling has
        // already been taken, so nothing new starts and the split stays exclusive.
        assertThat(result.getStepsToSave()).isEmpty();
    }

    @Test
    void choiceBreaksGuardLengthTiesDeterministicallyByStepId() {
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.COMPLETED);
        var bravo = choiceSuccessor("bravo", "choice", "x == '1'", StepExecutionStatus.CREATED);
        var alfa = choiceSuccessor("alfa", "choice", "y == '2'", StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(
                processWith("x", "1", "y", "2"), List.of(choice, bravo, alfa));

        // Equal-length guards, both true: the smaller step id wins, so the pick is stable.
        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("alfa");
    }

    @Test
    void choiceSuccessorsWaitUntilTheChoiceItselfCompletes() {
        var choice = se(step("choice", StepType.CHOICE, "start", null), StepExecutionStatus.PENDING);
        var a = choiceSuccessor("a", "choice", "status == 'vip'", StepExecutionStatus.CREATED);

        var result = service.calculateNextTransitions(processWith("status", "vip"), List.of(choice, a));

        // The CHOICE has not completed, so it has not decided anything yet.
        assertThat(result.getStepsToSave()).isEmpty();
    }

    @Test
    void aMessageStartStillArmsItselfWithNoPreconditions() {
        // The exception that keeps its reason: a flow entered by a message has to be subscribed
        // when the process is created, or the message it waits for finds nothing to correlate.
        var messageStart = new Step("wait", "wd-1", StepType.WAIT_FOR_MESSAGE, "Wait", null,
                null, null, null, false, null, null, null, null, null, 0, null,
                "payment-captured", "businessKey", null, 0, 0, false, null, 0, null);
        var waiting = se(messageStart, StepExecutionStatus.CREATED);
        var correlatable = Process.builder().id("p-1").businessKey("bk-1")
                .status(ProcessStatus.RUNNING).variables(List.of()).build();

        var result = service.calculateNextTransitions(correlatable, List.of(waiting));

        assertThat(result.getStepsToSave()).extracting(StepExecution::getStepId).containsExactly("wait");
        assertThat(result.getStepsToSave().get(0).getStatus()).isEqualTo(StepExecutionStatus.PENDING);
    }
}
