package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.in.async.processupstreamevent.ProcessUpstreamEventCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-MOMENT-06 on the durable path: the scheduler finds due timers through the indexed
 * {@code deadlineAt}, so a moved check-in has to be written there — this is what proves it is.
 */
class MomentJpaE2eTest extends AbstractJpaE2eTest {

    private static String checkIn(Duration fromNow, long daysAhead) {
        return ZonedDateTime.now().plusDays(daysAhead).plus(fromNow).toLocalDateTime().toString();
    }

    @Test
    void aMovedCheckInIsWhereTheSchedulerLooks() {
        worker.on("charge", TestWorker.succeed());

        createProcess("timer-moment-moved", "mo-jpa-1", new Variable("checkinAt", checkIn(Duration.ZERO, 10)));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("mo-jpa-1", "wait").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(step("mo-jpa-1", "modified").getStatus()).isEqualTo(StepExecutionStatus.PENDING));
        assertThat(step("mo-jpa-1", "wait").getDeadlineAt()).isAfter(LocalDateTime.now().plusDays(6));

        processUpstreamEventUseCase.handle(new ProcessUpstreamEventCommand(new MessageReceived(
                "booking-modified", "mo-jpa-1", List.of(new Variable("checkinAt", checkIn(Duration.ofMillis(600), 3))))));

        awaitStatus("mo-jpa-1", ProcessStatus.COMPLETED);
        assertThat(worker.invocationsOf("charge")).isEqualTo(1);
    }
}
