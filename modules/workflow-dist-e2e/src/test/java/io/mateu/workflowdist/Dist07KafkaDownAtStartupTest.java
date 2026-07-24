package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-07 — Orchestrator started while the Kafka broker is unavailable. Out of the box the
 * binder's startup topic provisioning blocks ~120s on the missing broker and loses its
 * AdminClient, so consumers never bind. With the binder's admin/request timeouts bounded and
 * binding retry enabled, the context boots promptly with the broker paused; once the broker
 * resumes the consumers bind and a process created through the upstream topic completes.
 */
class Dist07KafkaDownAtStartupTest extends AbstractDistTest {

    /**
     * Fail fast on provisioning + retry bindings, so a missing broker does not stall startup.
     * Mirrors exactly the production config now in the standalone apps' application.yaml (Kafka
     * requires default.api.timeout.ms >= request.timeout.ms).
     */
    static final Map<String, Object> RESILIENT_BINDER = Map.of(
            "spring.cloud.stream.kafka.binder.configuration[default.api.timeout.ms]", "15000",
            "spring.cloud.stream.kafka.binder.configuration[request.timeout.ms]", "10000",
            "spring.cloud.stream.bindingRetryInterval", "10");

    ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void worker() {
        DistInfra.ensureWorkerStarted(); // worker up while Kafka is up
    }

    @AfterEach
    void cleanup() {
        DistInfra.resumeKafka();
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void orchestratorBootsWithKafkaPausedThenProcessesWhenItResumes() {
        DistInfra.pauseKafka();

        long t0 = System.currentTimeMillis();
        orchestrator = DistInfra.startOrchestrator(RESILIENT_BINDER);
        long bootMs = System.currentTimeMillis() - t0;
        System.out.println("[chaos] orchestrator booted in " + bootMs + "ms with Kafka paused");
        assertThat(bootMs).as("boot must not block ~120s on the missing broker").isLessThan(60_000);

        DistInfra.resumeKafka();

        createProcess("dist-sequential-3", "dist07-1", new Variable("tenant", "acme"));
        awaitProcessCompleted("dist07-1");
        assertThat(completionPercentage("dist07-1")).isEqualTo(100);
        assertThat(stepStatuses("dist07-1"))
                .containsEntry("s1", "COMPLETED")
                .containsEntry("end", "COMPLETED");
        await("outbox drained").atMost(DEFAULT_TIMEOUT).until(() -> pendingOutboxMessages() == 0);
    }
}
