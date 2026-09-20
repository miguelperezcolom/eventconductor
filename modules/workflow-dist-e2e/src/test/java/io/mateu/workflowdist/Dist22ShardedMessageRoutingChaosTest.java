package io.mateu.workflowdist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.mateu.workflow.dtos.events.integration.MessageReceived;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflowdist.support.DistInfra;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DIST-22 — a genuinely <b>sharded</b> chaos test for message routing: two shards, each a full engine
 * with its <b>own database</b> and its own {@code upstream-i}/{@code outbox-i} topics, sharing the one
 * {@code messages} topic every shard consumes (each in its own group). It is the multi-shard companion
 * to DIST-21: the objective is the same — <b>no message lost, every waiter recovers</b> — but here a
 * resume must reach the <em>right</em> shard across a broker outage.
 *
 * <p>Processes are split across the two shards and parked on a {@code WAIT_FOR_MESSAGE}. The broker is
 * stopped; each resume is broadcast onto the shared {@code messages} topic (into the dead broker); and
 * while it is down nothing advances. When the broker returns, <b>both</b> shards receive every resume,
 * but only the shard whose database holds the waiting process correlates it — the other's per-shard
 * filter (layer 3) rules the key out and it runs no query. Every process on both shards must complete,
 * both outboxes must drain, and nothing may be dead-lettered.
 *
 * <p>This is where the cross-shard properties the single-node tests cannot reach are exercised for real:
 * the shared-topic fan-out, the per-shard filter deciding correctly on each shard, and recovery of the
 * message path itself across an outage.
 */
class Dist22ShardedMessageRoutingChaosTest {

    private static final String DEFINITION = "dist-wait-only";
    private static final int PROCESSES = 12;                 // 6 per shard
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final String[] SHARD_DB = {"shard0", "shard1"};
    private static final String FLEET_DB = "fleet0";

    static ConfigurableApplicationContext shard0;
    static ConfigurableApplicationContext shard1;

    @BeforeAll
    static void startShards() {
        DistInfra.ensureStarted();

        // Each shard its own database; one shared fleet database for the routing tables. Only the two
        // tables message routing reads are needed here (not the whole process-index read model), so
        // they are created directly rather than pulling Flyway onto the test classpath — the DDL is
        // the process-index migrations V2 (placement) and V4 (subscription), verbatim.
        DistInfra.createDatabase(SHARD_DB[0]);
        DistInfra.createDatabase(SHARD_DB[1]);
        DistInfra.createDatabase(FLEET_DB);
        var fleet = DistInfra.jdbcFor(FLEET_DB);
        fleet.execute("""
                CREATE TABLE IF NOT EXISTS process_placement (
                    business_key varchar(255) PRIMARY KEY,
                    shard_id     varchar(255) NOT NULL,
                    claimed_at   timestamp    NOT NULL
                )""");
        fleet.execute("""
                CREATE TABLE IF NOT EXISTS message_subscription (
                    step_execution_id varchar(255) PRIMARY KEY,
                    message_name      varchar(255) NOT NULL,
                    correlation_key   varchar(255) NOT NULL,
                    shard_id          varchar(255),
                    updated_at        timestamp    NOT NULL
                )""");
        fleet.execute("CREATE INDEX IF NOT EXISTS idx_message_subscription_name_key "
                + "ON message_subscription (message_name, correlation_key)");

        DistInfra.createTopics(List.of(
                "upstream-0", "upstream-1", "outbox-0", "outbox-1",
                "downstream-0", "downstream-1", "dead-letter-0", "dead-letter-1", "messages"));

        shard0 = startShard(0);
        shard1 = startShard(1);
    }

    @AfterAll
    static void stopShards() {
        if (shard0 != null) {
            shard0.close();
        }
        if (shard1 != null) {
            shard1.close();
        }
    }

    @AfterEach
    void ensureKafkaBack() {
        DistInfra.resumeKafka();
    }

    private static ConfigurableApplicationContext startShard(int shard) {
        // These must beat KafkaBindingDefaults (an EnvironmentPostProcessor that contributes the
        // binding destinations at lowest precedence) — the same way the k8s deployment does it with
        // high-precedence env vars. startOrchestrator passes its map through the builder's default
        // properties, which that post-processor still shadows; system properties sit above both. Set
        // them around the (synchronous) boot and clear them after, so nothing leaks to other contexts.
        var s = new java.util.LinkedHashMap<String, String>();
        // Its own database — the shard boundary.
        s.put("spring.datasource.url", DistInfra.jdbcUrlFor(SHARD_DB[shard]));
        s.put("spring.datasource.username", DistInfra.postgresUsername());
        s.put("spring.datasource.password", DistInfra.postgresPassword());
        // Its identity, the residual filter (layer 3), and the shared fleet database that backs the
        // subscription table and its projection.
        s.put("workflow.sharding.shard-id", String.valueOf(shard));
        s.put("workflow.sharding.message-routing.filter.enabled", "true");
        s.put("workflow.sharding.placement.datasource.url", DistInfra.jdbcUrlFor(FLEET_DB));
        s.put("workflow.sharding.placement.datasource.username", DistInfra.postgresUsername());
        s.put("workflow.sharding.placement.datasource.password", DistInfra.postgresPassword());
        // Bind the shared messages consumer alongside the usual two.
        s.put("spring.cloud.function.definition", "consumeOutbox;consumeUpstream;consumeMessages");
        s.put("spring.cloud.stream.bindings.consumeUpstream-in-0.destination", "upstream-" + shard);
        s.put("spring.cloud.stream.bindings.consumeUpstream-in-0.group", "orchestrator-upstream-" + shard);
        s.put("spring.cloud.stream.bindings.consumeUpstream-in-0.consumer.batch-mode", "true");
        s.put("spring.cloud.stream.bindings.consumeOutbox-in-0.destination", "outbox-" + shard);
        s.put("spring.cloud.stream.bindings.consumeOutbox-in-0.group", "orchestrator-outbox-" + shard);
        s.put("spring.cloud.stream.bindings.consumeOutbox-in-0.consumer.batch-mode", "true");
        // Shared destination, unique group per shard — so every shard sees every message.
        s.put("spring.cloud.stream.bindings.consumeMessages-in-0.destination", "messages");
        s.put("spring.cloud.stream.bindings.consumeMessages-in-0.group", "orchestrator-messages-" + shard);
        s.put("spring.cloud.stream.bindings.consumeMessages-in-0.consumer.batch-mode", "true");
        // Producer bindings onto this shard's own topics.
        s.put("spring.cloud.stream.bindings.upstream.destination", "upstream-" + shard);
        s.put("spring.cloud.stream.bindings.outbox.destination", "outbox-" + shard);
        s.put("spring.cloud.stream.bindings.downstream.destination", "downstream-" + shard);
        s.put("spring.cloud.stream.bindings.deadLetter.destination", "dead-letter-" + shard);
        s.put("spring.cloud.stream.bindings.messages.destination", "messages");
        s.put("logging.level.io.mateu.workflow", "WARN");
        s.forEach(System::setProperty);
        try {
            return DistInfra.startOrchestrator(new HashMap<>());
        } finally {
            s.keySet().forEach(System::clearProperty);
        }
    }

    private JdbcTemplate db(int shard) {
        return DistInfra.jdbcFor(SHARD_DB[shard]);
    }

    private long waiting(int shard) {
        return db(shard).queryForObject(
                "SELECT count(*) FROM step_execution_entity WHERE step_id = 'wait' AND status = 'PENDING'",
                Long.class);
    }

    private long completed(int shard) {
        return db(shard).queryForObject(
                "SELECT count(*) FROM process_entity WHERE status = 'COMPLETED'", Long.class);
    }

    private long pendingOutbox(int shard) {
        return db(shard).queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Pending'", Long.class);
    }

    private long erroredOutbox(int shard) {
        return db(shard).queryForObject(
                "SELECT count(*) FROM outbox_message_entity WHERE status = 'Error'", Long.class);
    }

    @Test
    @DisplayName("resumes broadcast during a broker outage reach the right shard; nothing is lost")
    void everyWaiterAcrossShardsRecoversAndNoResumeIsLost() throws Exception {
        // Create the batch, each process on shard i%2 (published to that shard's own upstream topic).
        for (var i = 0; i < PROCESSES; i++) {
            DistInfra.publishToAsync("upstream-" + (i % 2),
                    new ProcessCreationRequested(DEFINITION, "chaos-" + i, List.of()));
        }
        DistInfra.flushProducer();

        await("all processes parked on the wait step, split across both shards")
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> waiting(0) + waiting(1) >= PROCESSES);
        assertThat(waiting(0)).isEqualTo(PROCESSES / 2);
        assertThat(waiting(1)).isEqualTo(PROCESSES / 2);

        // Prime the producer's metadata for the shared `messages` topic while the broker is still up:
        // a producer that first touches a topic during the outage blocks on metadata for max.block.ms,
        // which is a property of the test's raw producer, not of the engine. One message for a key
        // nobody waits for — both shards receive it, correlate nothing and drop it (fail-closed).
        DistInfra.publishToAsync("messages", new MessageReceived("resume", "warmup-none", List.of()));
        DistInfra.flushProducer();

        // The broker disappears with every process waiting.
        DistInfra.pauseKafka();

        // Broadcast each resume onto the shared messages topic — into the dead broker (buffered).
        for (var i = 0; i < PROCESSES; i++) {
            DistInfra.publishToAsync("messages", new MessageReceived("resume", "chaos-" + i, List.of()));
        }

        // Nothing advances while the broker is gone.
        Thread.sleep(5_000);
        assertThat(completed(0) + completed(1)).isZero();

        // The broker returns: the buffered resumes are delivered to both shards, each correlates the
        // ones whose waiting process is in its own database, and every process finishes.
        DistInfra.resumeKafka();
        DistInfra.flushProducer();

        await("every process completed after recovery, on both shards")
                .atMost(TIMEOUT)
                .pollInterval(Duration.ofMillis(500))
                .until(() -> completed(0) + completed(1) >= PROCESSES);

        // Nothing lost: each shard finished exactly its own half.
        assertThat(completed(0)).isEqualTo(PROCESSES / 2);
        assertThat(completed(1)).isEqualTo(PROCESSES / 2);

        // Clean recovery on both shards: outboxes drained, nothing dead-lettered.
        for (var shard = 0; shard < 2; shard++) {
            var s = shard;
            await("shard " + s + " outbox drained").atMost(TIMEOUT).until(() -> pendingOutbox(s) == 0);
            assertThat(erroredOutbox(s)).as("shard %d dead-lettered", s).isZero();
        }
    }
}
