package io.mateu.workflowdist;

import io.mateu.workflow.dtos.Variable;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.WorkerStub;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-36 — long-lived processes and worker rate limits in Kafka mode: a CONTINUE_AS_NEW chain is created in the
 * step-over's transaction and each run carries on through the outbox, the key held by the latest run in the
 * UNIQUE business_key column (the earlier ones keep it in released_business_key); a rate-limited topic's tasks wait in deferred_dispatch (PostgreSQL) and reach the Kafka
 * worker one per window, their timeout not running while they wait.
 */
class Dist36FlowControlOverKafkaTest extends AbstractDistTest {

    static ConfigurableApplicationContext orchestrator;

    @BeforeAll
    static void startPods() {
        DistInfra.ensureWorkerStarted();
        orchestrator = DistInfra.startOrchestrator(Map.of(
                "workflow.workers.rate-limits.work.permits", "2",
                "workflow.workers.rate-limits.work.period", "PT1S",
                "workflow.workers.rate-limit-interval", "PT0.1S"));
    }

    @AfterAll
    static void stopPods() {
        orchestrator.close();
    }

    static String count(io.mateu.workflow.dtos.events.integration.TaskExecutionRequested request) {
        return request.variables().stream().filter(v -> "count".equals(v.name())).map(Variable::value).findFirst().orElse("0");
    }

    @Test
    void aContinueAsNewChainRunsOverKafka() {
        WorkerStub.on("dist-continue-as-new", "tick", (request, invocation) ->
                // Only the new count: echoing the task's variables too would report count twice.
                WorkerStub.sendStatus(request.taskExecutionId(), io.mateu.workflow.dtos.events.integration.TaskStatus.COMPLETED,
                        java.util.List.of(new Variable("count", String.valueOf(Integer.parseInt(count(request)) + 1))),
                        request.processId()));

        // The containers may outlive a run: a key of this run's own.
        var key = "dist36-chain-" + java.util.UUID.randomUUID();
        createProcess("dist-continue-as-new", key, new Variable("count", "0"));

        org.awaitility.Awaitility.await().atMost(DEFAULT_TIMEOUT).untilAsserted(() ->
                assertThat(DistInfra.jdbc().queryForObject("SELECT count(*) FROM process_entity WHERE (business_key = "
                        + "'" + key + "' OR released_business_key = '" + key + "') AND status = 'COMPLETED'", Integer.class)).isEqualTo(3));
        var linked = DistInfra.jdbc().queryForObject("SELECT count(*) FROM process_entity WHERE released_business_key = "
                + "'" + key + "' AND continued_as_process_id IS NOT NULL", Integer.class);
        assertThat(linked).isEqualTo(2);
        var last = DistInfra.jdbc().queryForObject("SELECT id FROM process_entity WHERE released_business_key IS NULL"
                + " AND business_key = '" + key + "'", String.class);
        assertThat(DistInfra.jdbc().queryForObject("SELECT continued_as_process_id FROM process_entity WHERE id = '"
                + last + "'", String.class)).isNull();
        assertThat(DistInfra.jdbc().queryForObject("SELECT count(*) FROM process_entity WHERE released_business_key = '"
                + key + "'", Integer.class)).isEqualTo(2);
    }

    @Test
    void aRateLimitedTopicReachesTheKafkaWorkerAtItsPace() {
        var received = new CopyOnWriteArrayList<Instant>();
        WorkerStub.on("dist-rate-limited", "call", (request, invocation) -> {
            received.add(Instant.now());
            WorkerStub.complete(request);
        });

        var run = java.util.UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= 6; i++) {
            createProcess("dist-rate-limited", "dist36-rl-" + run + "-" + i);
        }
        for (int i = 1; i <= 6; i++) {
            awaitProcessCompleted("dist36-rl-" + run + "-" + i);
        }
        var sorted = received.stream().sorted().toList();
        assertThat(sorted).hasSize(6);
        // Two per second: six tasks span at least two window changes; none timed out (800 ms) while waiting.
        assertThat(Duration.between(sorted.getFirst(), sorted.getLast())).isGreaterThanOrEqualTo(Duration.ofMillis(1800));
        // A redelivered dispatch may have been deferred once more; it is dropped as soon as a permit finds its
        // step no longer PENDING.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                DistInfra.jdbc().queryForObject("SELECT count(*) FROM deferred_dispatch", Integer.class)).isZero());
    }
}
