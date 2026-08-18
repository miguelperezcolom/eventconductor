package io.mateu.workflow.processindex;

import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.schema.ManagedSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC store is what the standalone projector writes through and what the engine reads a remote
 * read database through, so it is tested against a real database rather than mocked — and against the
 * shipped migration, so a column the SQL and the schema disagree about fails here.
 *
 * <p>H2 exercises the guarded read-then-write path (PostgreSQL takes the atomic {@code ON CONFLICT}
 * one). Both must uphold the same contract, which is what these assertions are: the strategy is an
 * implementation detail, the staleness guard is not.
 */
class JdbcProcessIndexStoreTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 8, 18, 10, 0);

    private DataSource dataSource;
    private JdbcProcessIndexStore store;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:index-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        // Applied exactly as the projector applies it, so the store's SQL is tested against the
        // shipped schema rather than against one the test invented.
        new ManagedSchema("process-index", "classpath:db/migration/processindex",
                "eventconductor_process_index_history").migrate(dataSource);
        store = new JdbcProcessIndexStore(dataSource);
    }

    @Test
    void upsertsAndReadsTheWholeRow() {
        store.upsert(row("p1", "order-4711", "RUNNING", 40, T0, "s2"));

        var found = store.findByProcessId("p1").orElseThrow();
        assertThat(found).isEqualTo(row("p1", "order-4711", "RUNNING", 40, T0, "s2"));
    }

    @Test
    void aSecondEventForTheSameProcessUpdatesItRatherThanDuplicatingIt() {
        store.upsert(row("p1", "order-4711", "RUNNING", 40, T0, "s2"));
        store.upsert(row("p1", "order-4711", "COMPLETED", 100, T0.plusMinutes(1), "s2"));

        assertThat(store.findByStatusIn(List.of("RUNNING"))).isEmpty();
        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void anOlderEventIsRejectedRatherThanClobberingTheNewerState() {
        store.upsert(row("p1", "order-4711", "COMPLETED", 100, T0.plusMinutes(5), "s2"));
        store.upsert(row("p1", "order-4711", "PENDING", 0, T0, "s2"));

        assertThat(store.findByProcessId("p1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void queriesTheFleetAcrossShards() {
        store.upsert(row("p1", "k1", "RUNNING", 10, T0, "s0"));
        store.upsert(row("p2", "k2", "RUNNING", 10, T0, "s1"));
        store.upsert(row("p3", "k3", "COMPLETED", 100, T0, "s1"));

        assertThat(store.findByStatusIn(List.of("RUNNING", "PENDING"))).hasSize(2);
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("order-fulfilment", List.of("RUNNING")))
                .hasSize(2);
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("something-else", List.of("RUNNING")))
                .isEmpty();
        assertThat(store.countByStatus()).isEqualTo(Map.of("RUNNING", 2L, "COMPLETED", 1L));
        assertThat(store.findByBusinessKey("k2").orElseThrow().shardId()).isEqualTo("s1");
        assertThat(store.findByBusinessKey("nope")).isEmpty();
    }

    @Test
    void anEmptyStatusFilterMatchesNothingRatherThanBuildingInvalidSql() {
        store.upsert(row("p1", "k1", "RUNNING", 10, T0, "s0"));

        assertThat(store.findByStatusIn(List.of())).isEmpty();
        assertThat(store.findByWorkflowDefinitionIdAndStatusIn("order-fulfilment", List.of())).isEmpty();
    }

    @Test
    void carriesNullsThroughForAProcessThatHasNotStartedOrFinished() {
        store.upsert(new ProcessIndexRow("p1", null, "order-fulfilment", 1, "PENDING", 0,
                T0, null, null, T0, null));

        var found = store.findByProcessId("p1").orElseThrow();
        assertThat(found.businessKey()).isNull();
        assertThat(found.started()).isNull();
        assertThat(found.finished()).isNull();
        assertThat(found.shardId()).isNull();
    }

    private static ProcessIndexRow row(String processId, String businessKey, String status,
                                       int completion, LocalDateTime updatedAt, String shardId) {
        return new ProcessIndexRow(processId, businessKey, "order-fulfilment", 1, status, completion,
                T0, T0, "COMPLETED".equals(status) ? updatedAt : null, updatedAt, shardId);
    }
}
