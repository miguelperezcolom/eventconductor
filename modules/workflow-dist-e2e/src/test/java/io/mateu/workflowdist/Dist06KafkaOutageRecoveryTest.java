package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-06 — Kafka broker outage while a process is in flight. Unlike DIST-02 (kill a pod +
 * freeze the relay), here the broker container itself is stopped and started under a healthy,
 * already-bound system — the same address returns after the outage.
 *
 * <p>The orchestrator and worker are started with Kafka up (consumers bound). A process is held
 * mid-flight, Kafka is stopped, and — once it returns — the process must reach COMPLETED with the
 * transactional outbox fully drained. Recovery is the engine's design guarantee: every state
 * transition is committed with its domain event in the same DB transaction (outbox), so nothing
 * is lost while Kafka is unreachable; it is relayed when the broker is back and the bound Kafka
 * consumers reconnect.
 */
class Dist06KafkaOutageRecoveryTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();            // worker up, Kafka up → consumer bound
        orchestrator = DistInfra.startOrchestrator(Map.of()); // orchestrator up, Kafka up → consumers bound
    }

    @AfterEach
    void ensureKafkaBack() {
        // Never leave the shared broker stopped for the next test class.
        DistInfra.resumeKafka();
    }

    @Test
    void kafkaDiesMidRunThenProcessRecovers() throws Exception {
        // Hold s1: the worker records the task but does not report back yet, so the process is
        // deterministically mid-flight when we cut Kafka.
        var s1 = new AtomicReference<TaskExecutionRequested>();
        WorkerStub.on("dist-sequential-3", "s1", (request, invocation) -> s1.set(request));

        createProcess("dist-sequential-3", "dist06-mid", new Variable("tenant", "acme"));
        await("s1 dispatched to the worker (in flight)").atMost(Duration.ofSeconds(60))
                .until(() -> s1.get() != null);

        // Kafka disappears with the process in flight.
        DistInfra.pauseKafka();

        // The worker reports s1 complete, but its reply cannot reach the down broker (it buffers
        // in the producer). Off-thread so a blocking send never stalls the test. The process must
        // not advance while Kafka is gone.
        new Thread(() -> WorkerStub.complete(s1.get()), "worker-reply").start();
        Thread.sleep(5_000);
        assertThat(processStatus("dist06-mid")).isNotEqualTo(Optional.of("COMPLETED"));

        // Kafka returns: the buffered reply is delivered and the orchestrator's outbox relay plus
        // its reconnected consumers drive s2, s3 and end home.
        DistInfra.resumeKafka();

        awaitProcessCompleted("dist06-mid");
        assertThat(completionPercentage("dist06-mid")).isEqualTo(100);
        assertThat(stepStatuses("dist06-mid"))
                .containsEntry("s1", "COMPLETED")
                .containsEntry("s2", "COMPLETED")
                .containsEntry("s3", "COMPLETED")
                .containsEntry("end", "COMPLETED");
        await("outbox fully drained after recovery").atMost(DEFAULT_TIMEOUT)
                .until(() -> pendingOutboxMessages() == 0);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Error'", Integer.class))
                .isZero();
    }
}
