package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static io.mateu.workflow.e2e.support.TestWorker.var;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The one shape of stuck that leaves no clock running anywhere, and the query that finds it.
 *
 * <p>A process reaches a branch, none of the guards on it is true, and it stops. Nothing else in
 * the engine will ever mention it again: the deadline scan is an index range over
 * {@code deadlineAt} and a step that never started has none; the stalled-<em>step</em> watch counts
 * live steps a worker owes an answer for and there are none, because every step here is either
 * finished or was never eligible.
 *
 * <p>Four processes on the reference deployment sat exactly like this for a week, and what found
 * them was a person asking why. Running the query written for this against that deployment's
 * 387 807 processes returned those four and a fifth nobody had noticed.
 *
 * <p>Note what makes the process stop <em>now</em>, after undefined variables became falsy: not a
 * missing variable — that would send it down the negative branch — but a value the definition has
 * no branch for. {@code decision} is {@code OTHER}, and the guards only know APPROVE and REJECT.
 * That is a gap in the definition, which is why the engine reports it rather than failing it.
 */
class StalledProcessJpaE2eTest extends AbstractJpaE2eTest {

    @Test
    void findsAProcessWhoseBranchNoGuardMatched() {
        worker.on("decide", TestWorker.succeed(var("decision", "OTHER")));

        createProcess("unmatched-branch", "stall-1");

        // It does not fail and it does not finish. It simply stops, which is the whole problem.
        awaitStalled("stall-1");

        assertThat(process("stall-1").getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(steps("stall-1")).noneMatch(step ->
                step.getStatus() == StepExecutionStatus.PENDING
                        || step.getStatus() == StepExecutionStatus.RUNNING);

        var stalled = processRepository.findStalled(LocalDateTime.now().plusMinutes(1), 20);
        assertThat(stalled).contains(process("stall-1").getId());
    }

    /**
     * The case that decides whether anyone leaves the watch switched on. A process that is working
     * normally must never be reported, and the window is what separates the two — so this asks the
     * same question with a threshold in the past instead of the future.
     */
    @Test
    void doesNotReportAProcessThatSimplyMovedRecently() {
        worker.on("decide", TestWorker.succeed(var("decision", "OTHER")));

        createProcess("unmatched-branch", "stall-2");
        awaitStalled("stall-2");

        // Stopped, but only just: anything that moved in the last hour is not yet a stall.
        assertThat(processRepository.findStalled(LocalDateTime.now().minusHours(1), 20))
                .doesNotContain(process("stall-2").getId());
    }

    @Test
    void doesNotReportAProcessThatCompleted() {
        worker.on("decide", TestWorker.succeed(var("decision", "APPROVE")));
        worker.on("approve", TestWorker.succeed());

        createProcess("unmatched-branch", "ok-1");
        awaitStatus("ok-1", ProcessStatus.COMPLETED);

        assertThat(processRepository.findStalled(LocalDateTime.now().plusMinutes(1), 20))
                .doesNotContain(process("ok-1").getId());
    }

    /** Waits for the process to have nothing left running — the definition of stopped here. */
    private void awaitStalled(String businessKey) {
        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(processOpt(businessKey)).isPresent();
            assertThat(steps(businessKey)).isNotEmpty();
            assertThat(steps(businessKey)).noneMatch(step ->
                    step.getStatus() == StepExecutionStatus.PENDING
                            || step.getStatus() == StepExecutionStatus.RUNNING);
        });
    }
}
