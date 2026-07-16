package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-01 — Kafka mode happy path. One orchestrator pod (kafka + jpa) and one Kafka
 * worker over real PostgreSQL + Kafka: a process created through the upstream topic
 * completes, every step is executed exactly once by the worker, and every domain event
 * flows through the outbox (all rows end Sent).
 */
class Dist01KafkaHappyPathTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
    }

    @Test
    void kafkaModeHappyPath() {
        createProcess("dist-sequential-3", "dist01-1", new Variable("tenant", "acme"));

        awaitProcessCompleted("dist01-1");

        assertThat(completionPercentage("dist01-1")).isEqualTo(100);
        assertThat(stepStatuses("dist01-1"))
                .containsEntry("s1", "COMPLETED")
                .containsEntry("s2", "COMPLETED")
                .containsEntry("s3", "COMPLETED")
                .containsEntry("end", "COMPLETED");

        // Exactly one worker execution per ACTION step, all for this process.
        var processId = processId("dist01-1");
        assertThat(WorkerStub.executionCount(processId, "s1")).isEqualTo(1);
        assertThat(WorkerStub.executionCount(processId, "s2")).isEqualTo(1);
        assertThat(WorkerStub.executionCount(processId, "s3")).isEqualTo(1);
        assertThat(WorkerStub.receivedFor(processId)).hasSize(3);

        // Every domain event went through the transactional outbox and was relayed.
        await("outbox fully relayed").atMost(DEFAULT_TIMEOUT)
                .until(() -> pendingOutboxMessages() == 0);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Error'", Integer.class))
                .isZero();
    }
}
