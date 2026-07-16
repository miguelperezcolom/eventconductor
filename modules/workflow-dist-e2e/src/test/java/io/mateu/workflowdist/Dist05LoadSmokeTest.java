package io.mateu.workflowdist;

import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DIST-05 — Load smoke. N=500 concurrent process instances (3 ACTION steps + END each,
 * i.e. 1500 worker task executions) driven through two orchestrator pods over real
 * PostgreSQL + Kafka. All must complete within the time bound with no lost or stuck
 * instances. The measured throughput (process instances/second, wall clock from first
 * publish to last completion observed) is printed as the comparison baseline recorded in
 * doc/guides/comparison.md.
 */
class Dist05LoadSmokeTest extends AbstractDistTest {

    static final int PROCESSES = 500;
    static final Duration TIME_BOUND = Duration.ofSeconds(300);

    static ConfigurableApplicationContext orchestrator1;
    static ConfigurableApplicationContext orchestrator2;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator1 = DistInfra.startOrchestrator(Map.of());
        orchestrator2 = DistInfra.startOrchestrator(Map.of());
    }

    @AfterAll
    static void stopPods() {
        orchestrator1.close();
        orchestrator2.close();
    }

    @Test
    void fiveHundredConcurrentProcessesCompleteWithinBound() {
        long start = System.nanoTime();
        for (int i = 0; i < PROCESSES; i++) {
            DistInfra.publishUpstreamAsync(
                    new ProcessCreationRequested("dist-sequential-3", "load-" + i, List.of()));
        }
        DistInfra.flushProducer();

        await(PROCESSES + " processes completed").atMost(TIME_BOUND)
                .pollInterval(Duration.ofMillis(500))
                .until(this::completedCount, count -> count == PROCESSES);
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        // No lost instances (every creation produced a process) and no stuck/failed ones.
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE 'load-%'", Integer.class))
                .isEqualTo(PROCESSES);
        assertThat(DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE 'load-%' AND status <> 'COMPLETED'",
                Integer.class))
                .isZero();
        await("outbox drained").atMost(DEFAULT_TIMEOUT).until(() -> pendingOutboxMessages() == 0);

        double throughput = PROCESSES / elapsedSeconds;
        // DB-side view, free of test polling overhead: first creation to last completion.
        Double engineSeconds = DistInfra.jdbc().queryForObject(
                "SELECT EXTRACT(EPOCH FROM (max(finished) - min(created))) FROM process_entity WHERE business_key LIKE 'load-%'",
                Double.class);
        System.out.printf("DIST-05 RESULT: %d process instances (3 ACTION steps each) in %.1f s wall clock"
                        + " -> %.1f process instances/second (engine-side window: %.1f s, %.1f PI/s)%n",
                PROCESSES, elapsedSeconds, throughput, engineSeconds, PROCESSES / engineSeconds);

        assertThat(elapsedSeconds).isLessThan(TIME_BOUND.getSeconds());
    }

    private int completedCount() {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE 'load-%' AND status = 'COMPLETED'",
                Integer.class);
    }
}
