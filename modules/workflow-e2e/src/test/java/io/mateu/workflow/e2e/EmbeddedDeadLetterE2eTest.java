package io.mateu.workflow.e2e;

import io.mateu.workflow.dtos.events.domain.StepExecutionStatusChanged;
import io.mateu.workflow.dtos.events.integration.TaskStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-DLQ-01 — Embedded mode parks what it cannot process.
 *
 * <p>There is no dead-letter topic here; the outbox table is the queue, so its {@code Error}
 * status is the resting place — the same one an undeserializable message already had. Visible in
 * the table, and replayable by putting the row back to {@code Pending}.
 *
 * <p>What makes it necessary is that the alternative is not "dropped", it is "retried for ever":
 * the relay leaves a failed message {@code Pending}, so without this an event that can never
 * succeed is re-attempted every cycle until someone notices the log.
 */
class EmbeddedDeadLetterE2eTest extends AbstractJpaE2eTest {

    @Test
    void anEventThatCanNeverSucceedIsParkedRatherThanRetriedForever() {
        // A status change for a step execution that does not exist: the handler looks it up and
        // throws, and will do so identically on every retry.
        var poison = new OutboxMessageEntity(new StepExecutionStatusChanged(
                "no-such-step-execution", TaskStatus.COMPLETED, List.of(), "no-such-process"));
        outboxRepository.save(poison);

        await("the poison message is parked").atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(outboxRepository.findById(poison.getId()))
                        .get()
                        .extracting(OutboxMessageEntity::getStatus)
                        .isEqualTo(OutboxMessageStatus.Error.name()));
    }

    @Test
    void aHealthyProcessStillRunsAlongsideAParkedMessage() {
        outboxRepository.save(new OutboxMessageEntity(new StepExecutionStatusChanged(
                "no-such-step-execution", TaskStatus.COMPLETED, List.of(), "no-such-process")));

        createProcess("sequential-3", "dlq-1");

        awaitStatus("dlq-1", io.mateu.workflow.domain.aggregates.ProcessStatus.COMPLETED);
    }
}
