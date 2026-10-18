package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loops end to end, in memory: {@code repeat} runs one step until a condition holds, {@code loopBack}
 * runs a section again — every pass a new execution, the earlier ones kept as history.
 */
class LoopsE2eTest extends AbstractE2eTest {

    private List<StepExecution> passes(String businessKey, String stepId) {
        return steps(businessKey).stream()
                .filter(s -> stepId.equals(s.getStepId()))
                .sorted(Comparator.comparingInt(StepExecution::getIteration))
                .toList();
    }

    @Test
    void repeatRunsTheStepUntilTheConditionHolds() {
        worker.on("poll", (req, cb, n) -> cb.complete(List.of(
                TestWorker.var("paymentStatus", n < 3 ? "PENDING" : "SETTLED"))));

        createProcess("repeat-poll", "rp-1");

        assertThat(worker.invocationsOf("poll")).isEqualTo(3);
        assertThat(worker.invocationsOf("ship")).isEqualTo(1);
        assertThat(process("rp-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var passes = passes("rp-1", "poll");
        assertThat(passes).extracting(StepExecution::getIteration).containsExactly(0, 1, 2);
        assertThat(passes).extracting(StepExecution::isSuperseded).containsExactly(true, true, false);
        assertThat(passes).extracting(StepExecution::getLoopDecision)
                .containsExactly(StepExecution.LOOP_LOOPED, StepExecution.LOOP_LOOPED, StepExecution.LOOP_EXITED);
        // Each run is its own task.
        assertThat(worker.received().stream().filter(r -> "poll".equals(r.stepId()))
                .map(r -> r.taskExecutionId()).distinct()).hasSize(3);
    }

    @Test
    void repeatPastMaxIterationsFailsTheStep() {
        worker.on("poll", TestWorker.succeed(TestWorker.var("paymentStatus", "PENDING")));

        createProcess("repeat-poll", "rp-max");

        assertThat(worker.invocationsOf("poll")).isEqualTo(4);
        assertThat(worker.invocationsOf("ship")).isZero();
        assertThat(process("rp-max").getStatus()).isEqualTo(ProcessStatus.ERROR);
        var last = passes("rp-max", "poll").get(3);
        assertThat(last.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(last.getLoopDecision()).isEqualTo(StepExecution.LOOP_EXHAUSTED);
        assertThat(errorsOf("rp-max")).anySatisfy(e -> assertThat(e).contains("maxIterations 4"));
    }

    @Test
    void theWorkflowsDefaultMaxStepExecutionsIsEnforced() {
        worker.on("poll", TestWorker.succeed(TestWorker.var("done", "no")));

        createProcess("repeat-capped", "rp-cap");

        // maxIterations says 10; defaultMaxStepExecutions says 3 successful runs, and wins.
        assertThat(worker.invocationsOf("poll")).isEqualTo(3);
        assertThat(process("rp-cap").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("rp-cap")).anySatisfy(e -> assertThat(e).contains("defaultMaxStepExecutions 3"));
    }

    @Test
    void aLoopBackOnAChoiceBranchRunsTheSectionAgainWithItsLatchesReset() {
        worker.on("review", (req, cb, n) -> cb.complete(List.of(
                TestWorker.var("verdict", n < 3 ? "REWORK" : "OK"))));

        createProcess("loopback-review", "lb-1");

        assertThat(worker.invocationsOf("draft")).isEqualTo(3);
        assertThat(worker.invocationsOf("review")).isEqualTo(3);
        assertThat(worker.invocationsOf("rework")).isEqualTo(2);
        assertThat(worker.invocationsOf("publish")).isEqualTo(1);
        assertThat(process("lb-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        // The CHOICE ran once per pass and picked afresh every time.
        assertThat(passes("lb-1", "decide")).hasSize(3)
                .allSatisfy(p -> assertThat(p.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED));
        // Steps outside the section ran once.
        assertThat(passes("lb-1", "start")).hasSize(1);
    }

    @Test
    void aLoopBackThatWouldPassItsMaxIterationsFails() {
        worker.on("review", TestWorker.succeed(TestWorker.var("verdict", "REWORK")));

        createProcess("loopback-review", "lb-max");

        assertThat(worker.invocationsOf("draft")).isEqualTo(3);
        assertThat(process("lb-max").getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(errorsOf("lb-max")).anySatisfy(e -> assertThat(e).contains("maxIterations 3"));
    }

    @Test
    void aLoopBackWithWhileReopensAForkAndItsJoin() {
        worker.on("check", (req, cb, n) -> cb.complete(List.of(TestWorker.var("again", n < 2 ? "yes" : "no"))));

        createProcess("loopback-while", "lb-fj");

        assertThat(worker.invocationsOf("a")).isEqualTo(2);
        assertThat(worker.invocationsOf("b")).isEqualTo(2);
        assertThat(worker.invocationsOf("check")).isEqualTo(2);
        assertThat(passes("lb-fj", "join")).hasSize(2);
        assertThat(process("lb-fj").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("lb-fj").getCompletionPercentage()).isEqualTo(100);
    }
}
