package io.mateu.workflow.e2e;

import io.mateu.workflow.application.readmodel.ProcessIndexQueryService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The CQRS process-index read model over the production path: {@code jpa} persistence with the real
 * outbox relay. Here {@link io.mateu.workflow.dtos.events.domain.ProcessStatusChanged} is not
 * dispatched in-process — it is written to the outbox, drained by the relay and only then projected,
 * so this proves the DB read-model adapter and its {@code occurredAt}-ordered upsert converge under
 * genuinely asynchronous, out-of-band delivery. Enabling the projection here is a single added
 * property on top of the JPA harness, confirming the feature is a config flip in the default,
 * non-sharded, single-database deployment.
 */
@TestPropertySource(properties = "workflow.projection.enabled=true")
class ProcessIndexProjectionJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessIndexQueryService processIndex;

    @Test
    void projectsThroughTheOutboxRelayToACompletedRow() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "jpa-idx-1");

        // The write side reaches COMPLETED first...
        awaitStatus("jpa-idx-1", ProcessStatus.COMPLETED);
        // ...and the read model catches up once the relay has drained the status events.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            var row = processIndex.findByBusinessKey("jpa-idx-1");
            assertThat(row).isPresent();
            assertThat(row.get().status()).isEqualTo(ProcessStatus.COMPLETED.name());
            assertThat(row.get().completionPercentage()).isEqualTo(100);
        });
        // A completed process is not in flight.
        assertThat(processIndex.findInFlight())
                .noneMatch(r -> "jpa-idx-1".equals(r.businessKey()));
    }
}
