package io.mateu.workflow.e2e;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import io.mateu.workflow.infra.out.persistence.OutboxMessageEntity;
import io.mateu.workflow.infra.out.persistence.OutboxMessageStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E-JPA-01/03 and the outbox poison-message guard — the engine driven end to end
 * through the real outbox relay and JDBC advisory locks on H2.
 */
class JpaDurabilityE2eTest extends AbstractJpaE2eTest {

    @Test
    void happyPathThroughOutboxCompletesAndDrainsAllMessages() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        // The JPA tests share one in-memory DB, so scope assertions to messages created
        // by THIS run (ignore rows other test methods left behind).
        var preexistingIds = outboxMessages().stream().map(OutboxMessageEntity::getId).collect(java.util.stream.Collectors.toSet());

        createProcess("sequential-3", "jpa-1");

        awaitStatus("jpa-1", ProcessStatus.COMPLETED);
        assertThat(process("jpa-1").getFinished()).isNotNull();
        assertThat(step("jpa-1", "s3").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);

        // Every domain event flowed through the outbox; once the process is done the relay
        // must have drained them all — nothing left Pending, and no message parked as Error.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var messages = outboxMessages().stream()
                    .filter(m -> !preexistingIds.contains(m.getId()))
                    .toList();
            assertThat(messages).isNotEmpty();
            assertThat(messages).noneMatch(m -> OutboxMessageStatus.Pending.name().equals(m.getStatus()));
            assertThat(messages).noneMatch(m -> OutboxMessageStatus.Error.name().equals(m.getStatus()));
            assertThat(messages).allMatch(m -> OutboxMessageStatus.Sent.name().equals(m.getStatus()));
        });
    }

    @Test
    void retriesExhaustedMarksProcessErrorOnJpa() {
        // Same failure semantics as memory mode → memory/JPA state-machine parity.
        worker.on("flaky", TestWorker.fail());

        createProcess("retry", "jpa-2");

        awaitStatus("jpa-2", ProcessStatus.ERROR);
        assertThat(step("jpa-2", "flaky").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(worker.invocationsOf("flaky")).isEqualTo(3); // 1 + 2 retries
    }

    @Test
    void poisonOutboxMessageIsParkedAsErrorNotRetriedForever() {
        // A row whose messageType is not an io.mateu.* event class must be quarantined,
        // not retried indefinitely (validates the outbox allowlist + error handling).
        var poison = new OutboxMessageEntity(
                UUID.randomUUID().toString(),
                LocalDateTime.now(),
                OutboxMessageStatus.Pending.name(),
                "com.evil.NotARealEvent",
                "{}",
                null);   // no trace: this row is hand-made, not produced by the engine
        outboxRepository.save(poison);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(outboxRepository.findById(poison.getId()))
                        .get()
                        .extracting(OutboxMessageEntity::getStatus)
                        .isEqualTo(OutboxMessageStatus.Error.name()));
    }
}
