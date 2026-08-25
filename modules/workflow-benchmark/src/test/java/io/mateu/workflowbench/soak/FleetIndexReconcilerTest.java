package io.mateu.workflowbench.soak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet checks against two shards and a fleet database, all real (H2). What is worth pinning is
 * that each check fails for the reason it exists: a lagging projector, a status the read model got
 * wrong, and a business key running on two shards at once.
 */
class FleetIndexReconcilerTest {

    private JdbcTemplate fleet;
    private JdbcTemplate shard0;
    private JdbcTemplate shard1;
    private Map<String, JdbcTemplate> shards;

    @BeforeEach
    void databases() {
        var suffix = System.nanoTime();
        fleet = jdbc("fleet-" + suffix);
        shard0 = jdbc("s0-" + suffix);
        shard1 = jdbc("s1-" + suffix);
        fleet.execute("""
                CREATE TABLE process_index (process_id varchar(255) PRIMARY KEY, business_key varchar(255),
                    status varchar(255), shard_id varchar(255))""");
        fleet.execute("""
                CREATE TABLE process_placement (business_key varchar(255) PRIMARY KEY,
                    shard_id varchar(255), claimed_at timestamp)""");
        for (var shard : new JdbcTemplate[]{shard0, shard1}) {
            shard.execute("""
                    CREATE TABLE process_entity (id varchar(255) PRIMARY KEY, business_key varchar(255),
                        status varchar(255))""");
        }
        shards = new LinkedHashMap<>(Map.of());
        shards.put("s0", shard0);
        shards.put("s1", shard1);
    }

    @Test
    void aFleetWhereEverythingAgreesPasses() {
        onShard(shard0, "p1", "soak-1", "COMPLETED");
        onShard(shard1, "p2", "soak-2", "COMPLETED");
        indexed("p1", "soak-1", "COMPLETED", "s0");
        indexed("p2", "soak-2", "COMPLETED", "s1");
        placed("soak-1", "s0");
        placed("soak-2", "s1");

        var verdict = FleetIndexReconciler.verify(fleet, shards, "soak-");
        assertThat(verdict.pass()).isTrue();
        assertThat(verdict.facts()).containsEntry("present", 2L).containsEntry("indexed", 2L);
    }

    @Test
    void aProjectorThatFellBehindFailsR8a() {
        onShard(shard0, "p1", "soak-1", "COMPLETED");
        onShard(shard1, "p2", "soak-2", "COMPLETED");
        indexed("p1", "soak-1", "COMPLETED", "s0");   // p2 never projected
        placed("soak-1", "s0");
        placed("soak-2", "s1");

        assertThat(failedChecks(FleetIndexReconciler.verify(fleet, shards, "soak-"))).contains("R8a");
    }

    @Test
    void aStaleEventThatClobberedATerminalStatusFailsR8b() {
        onShard(shard0, "p1", "soak-1", "COMPLETED");
        indexed("p1", "soak-1", "RUNNING", "s0");     // the seed event won, which it must not
        placed("soak-1", "s0");

        assertThat(failedChecks(FleetIndexReconciler.verify(fleet, shards, "soak-"))).contains("R8b");
    }

    @Test
    void oneBusinessKeyRunningOnTwoShardsFailsR8c() {
        // The duplicate the placement claim exists to prevent: one key, two processes, two shards.
        onShard(shard0, "p1", "soak-1", "COMPLETED");
        onShard(shard1, "p1-dup", "soak-1", "COMPLETED");
        indexed("p1", "soak-1", "COMPLETED", "s0");
        indexed("p1-dup", "soak-1", "COMPLETED", "s1");
        placed("soak-1", "s0");

        assertThat(failedChecks(FleetIndexReconciler.verify(fleet, shards, "soak-"))).contains("R8c");
    }

    @Test
    void aClaimedKeyWhoseCreationHasNotLandedYetIsNotADuplicate() {
        onShard(shard0, "p1", "soak-1", "RUNNING");
        indexed("p1", "soak-1", "RUNNING", "s0");
        placed("soak-1", "s0");
        placed("soak-2", "s1");   // claimed, creation still in flight

        assertThat(failedChecks(FleetIndexReconciler.verify(fleet, shards, "soak-"))).doesNotContain("R8c");
    }

    @Test
    void mergeKeepsBothVerdictsAndFailsIfEitherDoes() {
        var passing = new Reconciler.Verdict("soak-", true,
                java.util.List.of(new Reconciler.Check("R1", "conservation", 0, true)),
                Map.of("acked", 2L));
        var failing = new Reconciler.Verdict("soak-", false,
                java.util.List.of(new Reconciler.Check("R8a", "index complete", 1, false)),
                Map.of("indexed", 1L));

        var merged = FleetIndexReconciler.merge(passing, failing);
        assertThat(merged.pass()).isFalse();
        assertThat(merged.checks()).hasSize(2);
        assertThat(merged.facts()).containsKeys("acked", "indexed");
    }

    private java.util.List<String> failedChecks(Reconciler.Verdict verdict) {
        return verdict.checks().stream().filter(c -> !c.pass()).map(Reconciler.Check::id).toList();
    }

    private void onShard(JdbcTemplate shard, String id, String businessKey, String status) {
        shard.update("INSERT INTO process_entity (id, business_key, status) VALUES (?,?,?)",
                id, businessKey, status);
    }

    private void indexed(String processId, String businessKey, String status, String shardId) {
        fleet.update("INSERT INTO process_index (process_id, business_key, status, shard_id) VALUES (?,?,?,?)",
                processId, businessKey, status, shardId);
    }

    private void placed(String businessKey, String shardId) {
        fleet.update("INSERT INTO process_placement (business_key, shard_id, claimed_at) VALUES (?,?,now())",
                businessKey, shardId);
    }

    private static JdbcTemplate jdbc(String name) {
        return new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
    }
}
