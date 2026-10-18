package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * forEach and loops on embedded + JPA: iterations and passes are rows of their own (V47 columns),
 * driven through the outbox and the relay, asynchronously.
 */
class LoopsAndForEachJpaE2eTest extends AbstractJpaE2eTest {

    private static String var(TaskExecutionRequested request, String name) {
        return request.variables().stream().filter(v -> name.equals(v.name()))
                .map(Variable::value).findFirst().orElse(null);
    }

    private String processVariable(String businessKey, String name) {
        return process(businessKey).getVariables().stream().filter(v -> name.equals(v.name()))
                .map(io.mateu.workflow.domain.aggregates.Variable::value).findFirst().orElse(null);
    }

    @Test
    void aParallelForEachCollectsItsOutputInItemOrderThroughTheDatabase() {
        worker.on("reserve", (req, cb, n) -> cb.complete(List.of(TestWorker.var("reservationId", "R-" + var(req, "room")))));

        createProcess("foreach-rooms", "jfe-1", new Variable("rooms", "[\"101\",\"102\",\"103\"]"));

        awaitStatus("jfe-1", ProcessStatus.COMPLETED);
        assertThat(processVariable("jfe-1", "reservations")).isEqualTo("[\"R-101\",\"R-102\",\"R-103\"]");
        var iterations = steps("jfe-1").stream().filter(StepExecution::isIteration)
                .sorted(Comparator.comparing(StepExecution::getItemIndex)).toList();
        assertThat(iterations).hasSize(3).allSatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
            assertThat(i.getOutputJson()).contains("reservationId");
        });
        assertThat(iterations.get(2).scope()).containsEntry("room", "103").containsEntry("roomIndex", "2");
    }

    @Test
    void aForEachIterationFailureCompensatesTheCompletedOnes() {
        worker.on("reserve", (req, cb, n) -> {
            if ("2".equals(var(req, "room"))) cb.fail("full"); else cb.complete(List.of(TestWorker.var("reservationId", "R" + var(req, "room"))));
        });
        var released = new CopyOnWriteArrayList<String>();
        worker.on("release", (req, cb, n) -> {
            released.add(var(req, "reservationId"));
            cb.complete();
        });

        createProcess("foreach-rooms", "jfe-fail", new Variable("rooms", "[\"1\",\"2\",\"3\"]"));

        awaitStatus("jfe-fail", ProcessStatus.COMPENSATED);
        assertThat(released).containsExactlyInAnyOrder("R1", "R3");
    }

    @Test
    void aRepeatWithADelayRunsAgainAfterIt() {
        createProcess("repeat-delayed", "jrp-1");

        awaitStatus("jrp-1", ProcessStatus.COMPLETED);
        var passes = steps("jrp-1").stream().filter(s -> "poll".equals(s.getStepId()))
                .sorted(Comparator.comparingInt(StepExecution::getIteration)).toList();
        assertThat(passes).hasSize(3);
        assertThat(passes).extracting(StepExecution::getLoopDecision)
                .containsExactly(StepExecution.LOOP_LOOPED, StepExecution.LOOP_LOOPED, StepExecution.LOOP_EXITED);
        // Each run started at least the delay after the one before finished.
        assertThat(passes.get(1).getStartedAt()).isAfterOrEqualTo(passes.get(0).getFinishedAt().plusNanos(250_000_000));
    }

    @Test
    void aLoopBackOnAChoiceBranchRunsTheSectionAgain() {
        worker.on("review", (req, cb, n) -> cb.complete(List.of(TestWorker.var("verdict", n < 2 ? "REWORK" : "OK"))));

        createProcess("loopback-review", "jlb-1");

        awaitStatus("jlb-1", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("draft")).isEqualTo(2);
        assertThat(worker.invocationsOf("publish")).isEqualTo(1);
        assertThat(process("jlb-1").getCompletionPercentage()).isEqualTo(100);
        await().untilAsserted(() -> assertThat(steps("jlb-1").stream()
                .filter(s -> "decide".equals(s.getStepId()) && s.isSuperseded())).hasSize(1));
    }
}
