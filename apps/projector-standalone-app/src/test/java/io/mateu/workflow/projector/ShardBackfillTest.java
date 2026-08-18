package io.mateu.workflow.projector;

import io.mateu.workflow.application.readmodel.ProcessIndexProjection;
import io.mateu.workflow.dtos.events.domain.ProcessStatusChanged;
import io.mateu.workflow.processindex.JdbcProcessIndexStore;
import io.mateu.workflow.processindex.JdbcProcessPlacementStore;
import io.mateu.workflow.schema.ManagedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cutover step, against two real databases: a shard's write tables in, the read database out.
 *
 * <p>The two claims that matter are the ones that make a cutover safe to run on a live fleet — a
 * backfilled row must lose to any real transition, and a backfilled placement must never displace a
 * claim that already exists.
 */
class ShardBackfillTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 8, 18, 9, 0);

    private DataSource shard;
    private DataSource readDatabase;
    private JdbcProcessIndexStore index;
    private ShardBackfill backfill;

    @BeforeEach
    void twoDatabases() throws SQLException {
        var suffix = System.nanoTime();
        shard = new DriverManagerDataSource(
                "jdbc:h2:mem:shard-" + suffix + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        readDatabase = new DriverManagerDataSource(
                "jdbc:h2:mem:read-" + suffix + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(readDatabase);
        writeSideSchema();
        index = new JdbcProcessIndexStore(readDatabase);
        backfill = new ShardBackfill(index, readDatabase);
    }

    /** Just the columns the backfill reads — this is a query against a schema, not a mapping. */
    private void writeSideSchema() throws SQLException {
        execute("""
                CREATE TABLE process_entity (
                    id varchar(255) PRIMARY KEY,
                    business_key varchar(255),
                    workflow_definition_id varchar(255),
                    workflow_definition_version integer NOT NULL DEFAULT 0,
                    status varchar(255),
                    completion_percentage integer NOT NULL DEFAULT 0,
                    created timestamp, started timestamp, finished timestamp,
                    parent_step_execution_id varchar(255))""");
    }

    @Test
    void seedsTheIndexAndThePlacementsFromAShard() {
        insertProcess("p1", "order-1", "RUNNING", 40, null);
        insertProcess("p2", "order-2", "COMPLETED", 100, null);

        assertThat(backfill.backfill(shard, "s0")).isEqualTo(2);

        assertThat(index.countByStatus()).isEqualTo(Map.of("RUNNING", 1L, "COMPLETED", 1L));
        assertThat(index.findByBusinessKey("order-1").orElseThrow().shardId()).isEqualTo("s0");
        // And the claim now knows where those keys live, which is the point of seeding it at all:
        // a redelivered creation of order-1 must come back to s0, not be placed afresh.
        var placement = new JdbcProcessPlacementStore(readDatabase);
        assertThat(placement.claim("order-1", "s9")).isEqualTo("s0");
    }

    @Test
    void skipsChildrenBecauseAChildIsNeverPlacedOfItsOwn() {
        insertProcess("p1", "order-1", "RUNNING", 40, null);
        insertProcess("child", "parent:se-1", "RUNNING", 40, "se-1");

        assertThat(backfill.backfill(shard, "s0")).isEqualTo(1);
        assertThat(index.findByProcessId("child")).isEmpty();
    }

    @Test
    void aBackfilledRowLosesToARealTransitionHoweverTheyInterleave() {
        insertProcess("p1", "order-1", "RUNNING", 40, null);
        // The projector already saw this process finish, from the topic.
        ProcessIndexProjection.apply(index, new ProcessStatusChanged("p1", "order-1", "wd-1", 1,
                "COMPLETED", 100, CREATED, CREATED, CREATED.plusHours(1), CREATED.plusHours(1), "s0"));

        backfill.backfill(shard, "s0");

        // The backfill must not walk the read model backwards to the write side's stale snapshot.
        assertThat(index.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void anExistingPlacementIsNeverDisplaced() {
        new JdbcProcessPlacementStore(readDatabase).claim("order-1", "s1");
        insertProcess("p1", "order-1", "RUNNING", 40, null);

        backfill.backfill(shard, "s0");

        assertThat(new JdbcProcessPlacementStore(readDatabase).claim("order-1", "s9")).isEqualTo("s1");
    }

    @Test
    void runsTwiceWithTheSameResultSoACutoverCanBeRetried() {
        insertProcess("p1", "order-1", "RUNNING", 40, null);

        backfill.backfill(shard, "s0");
        backfill.backfill(shard, "s0");

        assertThat(index.countByStatus()).isEqualTo(Map.of("RUNNING", 1L));
    }

    private void insertProcess(String id, String businessKey, String status, int completion,
                               String parentStepExecutionId) {
        execute("""
                INSERT INTO process_entity (id, business_key, workflow_definition_id,
                    workflow_definition_version, status, completion_percentage, created, started,
                    finished, parent_step_execution_id)
                VALUES ('%s', '%s', 'wd-1', 1, '%s', %d, '%s', '%s', null, %s)"""
                .formatted(id, businessKey, status, completion, CREATED, CREATED,
                        parentStepExecutionId == null ? "null" : "'" + parentStepExecutionId + "'"));
    }

    private void execute(String sql) {
        try (var connection = shard.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
