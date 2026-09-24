package io.mateu.workflowdist;

import io.mateu.workflow.application.sync.SyncInvocationRejectedException;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import io.mateu.workflowdist.support.SyncCalls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DIST-26 — synchronous invocation on a sharded fleet. Two shards, each with its own database and
 * topics, sharing only the fleet's placement store. An invocation is placed on the shard that
 * received it and answered there; a retry with the same key that lands on the other shard does not
 * start a second process — it is told which shard owns the invocation; and a business key placed on
 * one shard cannot be taken by an invocation on the other.
 */
class Dist26ShardedSyncInvocationTest extends AbstractDistTest {

    private static final String[] SHARD = {"sa", "sb"};
    private static final String FLEET_DB = "fleet26";

    static ConfigurableApplicationContext shardA;
    static ConfigurableApplicationContext shardB;

    @BeforeAll
    static void startShards() {
        DistInfra.ensureStarted();
        DistInfra.createDatabase("shard_" + SHARD[0]);
        DistInfra.createDatabase("shard_" + SHARD[1]);
        DistInfra.createDatabase(FLEET_DB);
        DistInfra.jdbcFor(FLEET_DB).execute("""
                CREATE TABLE IF NOT EXISTS process_placement (
                    business_key varchar(255) PRIMARY KEY,
                    shard_id     varchar(255) NOT NULL,
                    claimed_at   timestamp    NOT NULL
                )""");
        DistInfra.createTopics(List.of("upstream-sa", "upstream-sb", "outbox-sa", "outbox-sb",
                "downstream-sa", "downstream-sb", "dead-letter-sa", "dead-letter-sb"));
        shardA = startShard(SHARD[0]);
        shardB = startShard(SHARD[1]);
    }

    @AfterAll
    static void stopShards() {
        if (shardA != null) shardA.close();
        if (shardB != null) shardB.close();
    }

    private static ConfigurableApplicationContext startShard(String shard) {
        // System properties, set around the boot, for the reason Dist22 gives: they must beat the
        // binding defaults the engine contributes.
        var s = new java.util.LinkedHashMap<String, String>();
        s.put("spring.datasource.url", DistInfra.jdbcUrlFor("shard_" + shard));
        s.put("spring.datasource.username", DistInfra.postgresUsername());
        s.put("spring.datasource.password", DistInfra.postgresPassword());
        s.put("workflow.sharding.enabled", "true");
        s.put("workflow.sharding.shard-id", shard);
        s.put("workflow.sharding.active-shards", String.join(",", SHARD));
        s.put("workflow.sharding.placement.datasource.url", DistInfra.jdbcUrlFor(FLEET_DB));
        s.put("workflow.sharding.placement.datasource.username", DistInfra.postgresUsername());
        s.put("workflow.sharding.placement.datasource.password", DistInfra.postgresPassword());
        s.put("spring.cloud.stream.bindings.consumeUpstream-in-0.destination", "upstream-" + shard);
        s.put("spring.cloud.stream.bindings.consumeUpstream-in-0.group", "orchestrator-upstream-" + shard);
        s.put("spring.cloud.stream.bindings.consumeOutbox-in-0.destination", "outbox-" + shard);
        s.put("spring.cloud.stream.bindings.consumeOutbox-in-0.group", "orchestrator-outbox-" + shard);
        s.put("spring.cloud.stream.bindings.upstream.destination", "upstream-" + shard);
        s.put("spring.cloud.stream.bindings.outbox.destination", "outbox-" + shard);
        s.put("spring.cloud.stream.bindings.downstream.destination", "downstream-" + shard);
        s.put("spring.cloud.stream.bindings.deadLetter.destination", "dead-letter-" + shard);
        s.put("workflow.sync.max-deadline-ms", "60000");
        s.forEach(System::setProperty);
        try {
            return DistInfra.startOrchestrator(new HashMap<>());
        } finally {
            s.keySet().forEach(System::clearProperty);
        }
    }

    @Test
    void anInvocationLivesOnTheShardThatReceivedIt() {
        var view = SyncCalls.invoke(shardA, "dist-sync-ack", "d26-k1", Map.of("orderId", "O-1"), Duration.ofSeconds(20));
        assertThat(view.replied()).isTrue();
        assertThat(view.reply().payload()).contains("O-1");
        assertThat(DistInfra.jdbcFor("shard_" + SHARD[0]).queryForObject(
                "select count(*) from process_entity where id = ?", Integer.class, view.invocation().processId())).isEqualTo(1);
        assertThat(DistInfra.jdbcFor(FLEET_DB).queryForObject(
                "select shard_id from process_placement where business_key = ?", String.class,
                "sync:dist-sync-ack:d26-k1")).isEqualTo(SHARD[0]);

        // The retry lands on the other shard: no second process, and it is told where to go.
        assertThatThrownBy(() -> SyncCalls.invoke(shardB, "dist-sync-ack", "d26-k1", Map.of("orderId", "O-1"),
                Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(SyncInvocationRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(SyncInvocationRejectedException.Reason.ON_ANOTHER_SHARD);
                    assertThat(e.shard()).isEqualTo(SHARD[0]);
                });
        assertThat(DistInfra.jdbcFor("shard_" + SHARD[1]).queryForObject(
                "select count(*) from sync_invocation where idempotency_key = 'd26-k1'", Integer.class)).isZero();

        // Back on the owning shard, the retry gets the one reply.
        var retried = SyncCalls.invoke(shardA, "dist-sync-ack", "d26-k1", Map.of("orderId", "O-1"), Duration.ofSeconds(5));
        assertThat(retried.invocation().processId()).isEqualTo(view.invocation().processId());
    }

    @Test
    void aBusinessKeyPlacedOnOneShardCannotBeTakenOnTheOther() {
        var service = shardB.getBean(io.mateu.workflow.application.sync.SyncInvocationService.class);
        var started = service.start(new io.mateu.workflow.application.sync.SyncInvocationService.StartRequest(
                "dist-sync-ack", "d26-k2", "BK-26", Map.of("orderId", "O-2"), Duration.ofSeconds(10), null));
        assertThat(service.await(started.invocation(), started.timeToWait()).join().replied()).isTrue();

        var other = shardA.getBean(io.mateu.workflow.application.sync.SyncInvocationService.class);
        assertThatThrownBy(() -> other.start(new io.mateu.workflow.application.sync.SyncInvocationService.StartRequest(
                "dist-sync-ack", "d26-k3", "BK-26", Map.of("orderId", "O-3"), Duration.ofSeconds(5), null)))
                .isInstanceOfSatisfying(SyncInvocationRejectedException.class, e -> assertThat(e.shard()).isEqualTo(SHARD[1]));
    }
}
