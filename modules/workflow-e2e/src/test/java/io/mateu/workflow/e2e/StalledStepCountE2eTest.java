package io.mateu.workflow.e2e;

import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * What {@code eventconductor.steps.stalled} counts — the gauge the observability guide names as
 * the one to alert on.
 *
 * <p>Both steps here are live, and neither has a deadline. That is the whole difficulty: the
 * engine cannot time out either of them, so on the deadline scan they look identical. One is a
 * process stopped dead — a worker was asked to do something and never answered — and the other is
 * a person who has not got round to approving something yet, which is not a fault, and in a
 * business process is most of the elapsed time.
 *
 * <p>Counting both made the gauge permanently non-zero in any deployment with human tasks, with a
 * warning logged every minute on every pod telling the operator to put a timeout on steps that are
 * unbounded on purpose. An alert that is always firing is not an alert.
 */
class StalledStepCountE2eTest extends AbstractJpaE2eTest {

    @Test
    void countsAnActionWaitingOnAWorkerButNeverAWaitingHumanTask() {
        // Far enough ahead that every step started during this test counts as "waiting since
        // before", so the type is the only thing separating the two cases below. The baseline
        // absorbs whatever other JPA tests left behind in the shared in-memory database.
        var cutoff = LocalDateTime.now().plusMinutes(1);
        var baseline = stepExecutionRepository.countStalled(cutoff);

        worker.on("approve", TestWorker.deferForever());
        createProcess("usertask", "stalled-human");
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("stalled-human", "approve").getStartedAt()).isNotNull());

        assertThat(stepExecutionRepository.countStalled(cutoff))
                .as("a human task waiting for a person is not stalled work")
                .isEqualTo(baseline);

        worker.on("s1", TestWorker.deferForever());
        createProcess("sequential-3", "stalled-action");
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("stalled-action", "s1").getStartedAt()).isNotNull());

        assertThat(stepExecutionRepository.countStalled(cutoff))
                .as("an ACTION whose worker never answered is exactly what the gauge is for")
                .isEqualTo(baseline + 1);
    }
}
