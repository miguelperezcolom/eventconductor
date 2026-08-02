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

    /**
     * Two by default, as the published baseline was measured. Overridable with -Dtune.pods so the
     * one question a distributed engine has to answer — does adding a pod add throughput? — can
     * be measured rather than argued.
     */
    static final int PODS = Integer.getInteger("tune.pods", 2);

    static final java.util.List<ConfigurableApplicationContext> orchestrators = new java.util.ArrayList<>();

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        for (var i = 0; i < PODS; i++) {
            orchestrators.add(DistInfra.startOrchestrator(Map.of()));
        }
    }

    @AfterAll
    static void stopPods() {
        orchestrators.forEach(ConfigurableApplicationContext::close);
        orchestrators.clear();
    }

    /** Committed transactions on the workload database, for attributing the cost of a run. */
    private long committedTransactions() {
        var count = DistInfra.jdbc().queryForObject(
                "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()",
                Long.class);
        return count == null ? 0 : count;
    }

    @Test
    void fiveHundredConcurrentProcessesCompleteWithinBound() {
        var transactionsBefore = committedTransactions();
        long start = System.nanoTime();
        for (int i = 0; i < PROCESSES; i++) {
            DistInfra.publishUpstreamAsync(
                    new ProcessCreationRequested("dist-sequential-3", "load-" + i, List.of()));
        }
        DistInfra.flushProducer();

        await(PROCESSES + " processes completed").atMost(TIME_BOUND)
                // 20ms, not 500: the poll interval lands straight in the measurement, and half a
                // second of it against a ten-second run is noise the number does not need.
                .pollInterval(Duration.ofMillis(20))
                .until(this::completedCount, count -> count == PROCESSES);
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
        var transactions = committedTransactions() - transactionsBefore;

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
        var steps = PROCESSES * 3;
        System.out.printf("DIST-05 RESULT: %d process instances (3 ACTION steps each) in %.1f s wall clock"
                        + " -> %.1f process instances/second (engine-side window: %.1f s, %.1f PI/s)%n",
                PROCESSES, elapsedSeconds, throughput, engineSeconds, PROCESSES / engineSeconds);
        // What the database was actually asked to do. Note what this counts: PostgreSQL's
        // xact_commit includes implicit transactions, so a statement issued outside one is a
        // commit here too. Read it as database chattiness rather than as write transactions —
        // it is a cost signal, not an accounting of writes.
        //
        // Its purpose is to answer whether the throughput above is bounded by the database at
        // all. Measured on this machine it is not: pushing the poll interval from 200ms to 5ms
        // takes it from ~820 to ~1350 commits/s and moves throughput by under 10%, so the
        // database is absorbing far more traffic than the pipeline can turn into work.
        System.out.printf("DIST-05 COST: %d commits over the run -> %.0f commits/s, %.2f commits per step"
                        + " (%d steps) | pods=%d %s%n",
                transactions, transactions / elapsedSeconds, transactions / (double) steps,
                steps, PODS, DistInfra.tuning());

        assertThat(elapsedSeconds).isLessThan(TIME_BOUND.getSeconds());
    }

    private int completedCount() {
        return DistInfra.jdbc().queryForObject(
                "SELECT count(*) FROM process_entity WHERE business_key LIKE 'load-%' AND status = 'COMPLETED'",
                Integer.class);
    }
}
