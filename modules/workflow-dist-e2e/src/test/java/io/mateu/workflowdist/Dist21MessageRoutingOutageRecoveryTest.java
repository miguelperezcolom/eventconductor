package io.mateu.workflowdist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * DIST-21 — a Kafka broker outage across the message-correlation path, with the per-shard filter
 * (layer 3 of sharded message routing) on the correlation path.
 *
 * <p>The objective this pins is the one the feature must never trade away for its query savings:
 * <b>no message is lost and every waiting process recovers</b>. A batch of processes is driven to a
 * {@code WAIT_FOR_MESSAGE} step and parked; the broker is then stopped, each process's resume message
 * is published into the dead broker (buffered in the producer), and while it is down nothing moves;
 * the broker returns and every process must correlate its resume and run to completion.
 *
 * <p>The filter (`workflow.sharding.message-routing.filter.enabled`) is on, so every resume is
 * checked against it before the correlation query. That is exactly where a false negative would drop
 * a message — a step is waiting, the filter wrongly says "no", the message is discarded — so a run
 * where all processes complete is the end-to-end evidence that it never does, on top of the unit
 * invariant in {@code WaitingMessageFilterTest}. Recovery itself is the engine's outbox guarantee
 * (see DIST-06); this adds the message path and the filter to it.
 */
class Dist21MessageRoutingOutageRecoveryTest extends AbstractDistTest {

    private static final String DEFINITION = "dist-wait-message";
    private static final int PROCESSES = 20;

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        // The residual per-shard filter on the correlation path — the layer under test. With no
        // shard id (single cluster) it still filters over this node's own waiting steps.
        orchestrator = DistInfra.startOrchestrator(
                Map.of("workflow.sharding.message-routing.filter.enabled", "true"));
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @AfterEach
    void ensureKafkaBack() {
        DistInfra.resumeKafka();
    }

    private long processesWaiting() {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM step_execution_entity s JOIN process_entity p ON p.id = s.process_id "
                        + "WHERE p.business_key LIKE 'chaos-%' AND s.step_id = 'wait' AND s.status = 'PENDING'",
                Long.class);
    }

    private long processesCompleted() {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE 'chaos-%' AND status = 'COMPLETED'",
                Long.class);
    }

    @Test
    @DisplayName("resume messages published during a broker outage are not lost; every waiter recovers")
    void everyWaiterRecoversAndNoResumeIsLost() throws Exception {
        WorkerStub.on(DEFINITION, "s1", WorkerStub::completeNow);
        WorkerStub.on(DEFINITION, "s2", WorkerStub::completeNow);

        // Create the batch and let each run its first step and park on the wait — this is also what
        // populates the filter (each waiting step adds its (name, key) pair).
        for (var i = 0; i < PROCESSES; i++) {
            createProcess(DEFINITION, "chaos-" + i);
        }
        await("all " + PROCESSES + " processes parked on the wait step")
                .atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(250))
                .until(() -> processesWaiting() >= PROCESSES);

        // The broker disappears with every process waiting.
        DistInfra.pauseKafka();

        // Publish each resume into the dead broker: the sends buffer in the producer rather than
        // reaching anyone. Async so a blocking send never stalls the test.
        for (var i = 0; i < PROCESSES; i++) {
            DistInfra.publishUpstreamAsync(new MessageReceived("resume", "chaos-" + i, List.of()));
        }

        // Nothing may advance while the broker is gone — the resumes are in limbo, not delivered.
        Thread.sleep(5_000);
        assertThat(processesCompleted()).isZero();

        // The broker returns: the buffered resumes are delivered, correlated (past the filter) and
        // every process runs its final step home.
        DistInfra.resumeKafka();
        DistInfra.flushProducer();

        await("every process completed after recovery")
                .atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> processesCompleted() >= PROCESSES);

        // Nothing lost: all of them, each having correlated its resume and finished.
        assertThat(processesCompleted()).isEqualTo(PROCESSES);
        for (var i = 0; i < PROCESSES; i++) {
            assertThat(stepStatuses("chaos-" + i))
                    .containsEntry("wait", "COMPLETED")
                    .containsEntry("s2", "COMPLETED")
                    .containsEntry("end", "COMPLETED");
        }

        // The outbox drained and nothing dead-lettered — recovery was clean, not partial.
        await("outbox fully drained after recovery").atMost(DEFAULT_TIMEOUT)
                .until(() -> pendingOutboxMessages() == 0);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Error'", Integer.class))
                .isZero();
    }
}
