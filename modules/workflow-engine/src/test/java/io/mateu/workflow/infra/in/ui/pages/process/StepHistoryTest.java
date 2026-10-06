package io.mateu.workflow.infra.in.ui.pages.process;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.JoinType;
import io.mateu.workflow.domain.aggregates.Precondition;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.CANCELLED;
import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.COMPLETED;
import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.CREATED;
import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.ERROR;
import static io.mateu.workflow.domain.aggregates.StepExecutionStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Steps tab lists what the run reached and the path ahead that is certain — not every step the
 * engine materialised when it created the process.
 */
class StepHistoryTest {

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-10-05T12:00:00");

    private final List<StepExecution> run = new ArrayList<>();

    private static Step step(String id, StepType type, List<Precondition> links) {
        return new Step(id, "wd-1", type, id, null, null, null,
                links, null, false, "topic", null, null, null, null, 0, null, null,
                null, null, 0, 0, false, null, null, 0, null, List.of(), List.of());
    }

    private static Precondition after(String stepId) {
        return new Precondition(stepId, null);
    }

    private static Precondition after(String stepId, String guard) {
        return new Precondition(stepId, guard);
    }

    /** One execution as the engine stores it: the frozen step as JSON, materialised in definition order. */
    private StepExecution exec(Step step, StepExecutionStatus status, Integer startedAtSecond) {
        var started = startedAtSecond == null ? null : T0.plusSeconds(startedAtSecond);
        var se = StepExecution.builder()
                .id("se-" + step.id())
                .processId("p-1")
                .workflowDefinitionId("wd-1")
                .stepId(step.id())
                .stepJson(JsonSerializer.toJson(step))
                .variables(List.of())
                .status(status)
                .order(run.size() + 1)
                .startedAt(started)
                .finishedAt(status.isTerminal() && started != null ? started.plusSeconds(1) : null)
                .build();
        run.add(se);
        return se;
    }

    private List<String> listed(ProcessStatus processStatus) {
        return StepHistory.visible(run, processStatus).stream()
                .map(entry -> entry.execution().getStepId() + (entry.ahead() ? " (next)" : ""))
                .toList();
    }

    @Test
    void aCompletedProcessListsOnlyTheBranchItTook() {
        // start → choice → (vip: gift → end) | (otherwise: plain → end)
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("choice", StepType.CHOICE, List.of(after("start"))), COMPLETED, 1);
        exec(step("gift", StepType.ACTION, List.of(after("choice", "vip"))), CREATED, null);
        exec(step("plain", StepType.ACTION, List.of(after("choice"))), COMPLETED, 2);
        exec(step("end", StepType.END, List.of(after("gift"), after("plain"))), COMPLETED, 3);

        // Before, every one of them — «gift» as Created, as if the process still had it to do.
        assertThat(listed(ProcessStatus.COMPLETED)).containsExactly("start", "choice", "plain", "end");
    }

    @Test
    void aRunningProcessListsTheCertainSequenceAheadUpToTheNextDecision() {
        // start → reserve → charge → decide (CHOICE) → ship | refund ; ship → end
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("reserve", StepType.ACTION, List.of(after("start"))), RUNNING, 1);
        exec(step("charge", StepType.ACTION, List.of(after("reserve"))), CREATED, null);
        exec(step("decide", StepType.CHOICE, List.of(after("charge"))), CREATED, null);
        exec(step("ship", StepType.ACTION, List.of(after("decide", "paid"))), CREATED, null);
        exec(step("refund", StepType.ACTION, List.of(after("decide"))), CREATED, null);
        exec(step("end", StepType.END, List.of(after("ship"), after("refund"))), CREATED, null);

        // The decision itself is certain to be reached; what it decides is not.
        assertThat(listed(ProcessStatus.RUNNING))
                .containsExactly("start", "reserve", "charge (next)", "decide (next)");
    }

    @Test
    void aGuardedLinkIsADecisionToo() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("check", StepType.ACTION, List.of(after("start"))), RUNNING, 1);
        exec(step("notify", StepType.ACTION, List.of(after("check", "amount > 100"))), CREATED, null);
        exec(step("end", StepType.END, List.of(after("notify"))), CREATED, null);

        assertThat(listed(ProcessStatus.RUNNING)).containsExactly("start", "check");
    }

    @Test
    void anAndJoinIsCertainOnlyWhenEveryBranchIntoItIs() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("fork", StepType.FORK, List.of(after("start"))), COMPLETED, 1);
        exec(step("left", StepType.ACTION, List.of(after("fork"))), RUNNING, 2);
        exec(step("right", StepType.ACTION, List.of(after("fork"))), RUNNING, 2);
        exec(step("optional", StepType.ACTION, List.of(after("right", "deep"))), CREATED, null);
        exec(step("join", StepType.JOIN, List.of(after("left"), after("right"))), CREATED, null);
        exec(step("waitsForOptional", StepType.JOIN, List.of(after("left"), after("optional"))), CREATED, null);

        assertThat(listed(ProcessStatus.RUNNING)).containsExactly("start", "fork", "left", "right", "join (next)");
    }

    @Test
    void anXorJoinNeedsOnlyOneCertainBranch() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("a", StepType.ACTION, List.of(after("start"))), RUNNING, 1);
        exec(step("b", StepType.ACTION, List.of(after("start", "never"))), CREATED, null);
        exec(step("join", StepType.JOIN, List.of(after("a"), after("b"))).withJoinType(JoinType.XOR), CREATED, null);

        assertThat(listed(ProcessStatus.RUNNING)).containsExactly("start", "a", "join (next)");
    }

    @Test
    void nothingIsAheadOfAFailedStep() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("call", StepType.ACTION, List.of(after("start"))), ERROR, 1);
        exec(step("after", StepType.ACTION, List.of(after("call"))), CREATED, null);

        assertThat(listed(ProcessStatus.ERROR)).containsExactly("start", "call");
    }

    @Test
    void aCancellationDoesNotListTheStepsItSweptUpBeforeTheRunReachedThem() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("approve", StepType.USER_TASK, List.of(after("start"))), CANCELLED, 1);
        exec(step("ship", StepType.ACTION, List.of(after("approve"))), CANCELLED, null);

        assertThat(listed(ProcessStatus.CANCELLED)).containsExactly("start", "approve");
    }

    @Test
    void aStepQueuedAgainForARetryIsListedAsReached() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        var retried = exec(step("call", StepType.ACTION, List.of(after("start"))), CREATED, null)
                .toBuilder().attemptCount(1).build();
        run.set(1, retried);

        assertThat(StepHistory.visible(run, ProcessStatus.RUNNING))
                .extracting(entry -> entry.execution().getStepId() + ":" + entry.ahead())
                .containsExactly("start:false", "call:false");
    }

    @Test
    void reachedStepsAreListedInTheOrderTheyTookTheirTurn() {
        exec(step("start", StepType.START, List.of()), COMPLETED, 0);
        exec(step("slow", StepType.ACTION, List.of(after("start"))), COMPLETED, 5);
        exec(step("fast", StepType.ACTION, List.of(after("start"))), COMPLETED, 2);

        assertThat(listed(ProcessStatus.COMPLETED)).containsExactly("start", "fast", "slow");
    }
}
