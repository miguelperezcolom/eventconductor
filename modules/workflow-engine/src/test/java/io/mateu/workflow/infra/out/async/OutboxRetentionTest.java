package io.mateu.workflow.infra.out.async;

import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import io.mateu.workflow.infra.out.persistence.H2DbLockDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention runs a {@code DELETE} against the engine's own outbox, so the claim that it only ever
 * removes delivered messages is worth executing rather than reading. Everything here goes through a
 * real database and the real dialect SQL.
 */
class OutboxRetentionTest {

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private OutboxRetention retention;

    private static final LocalDateTime NOW = LocalDateTime.now();

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        // Column names and types copied from the engine's own V1__baseline.sql, so the dialect SQL
        // under test runs against the shape it will meet in a real deployment.
        jdbcTemplate.execute("""
                CREATE TABLE outbox_message_entity (
                    id            VARCHAR(255) PRIMARY KEY,
                    timestamp     TIMESTAMP,
                    status        VARCHAR(64),
                    message_type  VARCHAR(512),
                    payload       TEXT,
                    trace_parent  VARCHAR(64)
                )""");
        retention = new OutboxRetention(jdbcTemplate, new H2DbLockDialect(),
                new TransactionTemplate(new DataSourceTransactionManager(database)));
        retention.retention = Duration.ofHours(1);
        retention.purgeBatchSize = 1000;
        retention.maxBatchesPerPass = 20;
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private void row(String id, String status, Duration age) {
        jdbcTemplate.update("INSERT INTO outbox_message_entity VALUES (?, ?, ?, ?, ?, ?)",
                id, Timestamp.valueOf(NOW.minus(age)), status, "io.mateu.Whatever", "{}", null);
    }

    private List<String> remainingIds() {
        return jdbcTemplate.queryForList("SELECT id FROM outbox_message_entity ORDER BY id", String.class);
    }

    @Test
    void removesOnlyMessagesThatWereActuallySent() {
        // Pending is undelivered work and Error is a parked message someone has to look at. Age
        // alone must never be enough to remove either — that is the whole safety argument.
        row("old-sent", "Sent", Duration.ofHours(5));
        row("old-pending", "Pending", Duration.ofHours(5));
        row("old-error", "Error", Duration.ofHours(5));

        var purged = retention.purge();

        assertThat(purged).isEqualTo(1);
        assertThat(remainingIds()).containsExactly("old-error", "old-pending");
    }

    @Test
    void keepsSentMessagesThatAreStillInsideTheWindow() {
        row("recent-sent", "Sent", Duration.ofMinutes(10));
        row("old-sent", "Sent", Duration.ofHours(5));

        var purged = retention.purge();

        assertThat(purged).isEqualTo(1);
        assertThat(remainingIds()).containsExactly("recent-sent");
    }

    @Test
    void deletesInBoundedBatchesRatherThanOneStatement() {
        // The bound is not a detail: an unbounded delete over a table this job exists for is one
        // transaction and one lock held long enough to matter to the relays sharing that database.
        for (var i = 0; i < 5; i++) {
            row("old-" + i, "Sent", Duration.ofHours(5));
        }
        retention.purgeBatchSize = 2;

        var purged = retention.purge();

        assertThat(purged).isEqualTo(5);
        assertThat(remainingIds()).isEmpty();
    }

    @Test
    void aPassIsCappedSoCatchingUpNeverMonopolisesTheDatabase() {
        for (var i = 0; i < 10; i++) {
            row("old-" + i, "Sent", Duration.ofHours(5));
        }
        retention.purgeBatchSize = 2;
        retention.maxBatchesPerPass = 3;

        assertThat(retention.purge()).isEqualTo(6);
        assertThat(remainingIds()).hasSize(4);
        // And the next pass picks up where this one stopped, rather than starting over.
        assertThat(retention.purge()).isEqualTo(4);
        assertThat(remainingIds()).isEmpty();
    }

    @Test
    void anEmptyOutboxIsNotAnError() {
        assertThat(retention.purge()).isZero();
    }

    @Test
    void everyDialectFiltersOnSentAndNothingElse() {
        // The filter is repeated per dialect rather than shared, so it is worth asserting that no
        // dialect has drifted: a purge that removed Pending rows would delete undelivered work.
        List<DbLockDialect> dialects = List.of(
                new H2DbLockDialect(),
                new io.mateu.workflow.infra.out.persistence.PostgresDbLockDialect(),
                new io.mateu.workflow.infra.out.persistence.MariaDbLockDialect(),
                new io.mateu.workflow.infra.out.persistence.OracleDbLockDialect());

        assertThat(dialects).allSatisfy(dialect -> {
            var sql = dialect.selectSentOutboxToPurgeSql();
            assertThat(sql).contains("status = 'Sent'");
            assertThat(sql).doesNotContain("Pending").doesNotContain("Error");
            // It selects; the delete is by primary key. A dialect that started deleting here would
            // reintroduce the unbounded-delete problem this shape exists to avoid.
            assertThat(sql).doesNotContainIgnoringCase("DELETE");
        });
    }

    @Test
    void aBatchDeletesExactlyItsBatchSize() {
        // The regression this shape exists for. The obvious single-DELETE form asked H2 for two
        // rows and removed one, which would have left retention permanently behind the table while
        // looking like it worked.
        for (var i = 0; i < 10; i++) {
            row("old-" + i, "Sent", Duration.ofHours(5));
        }
        retention.purgeBatchSize = 2;
        retention.maxBatchesPerPass = 1;

        assertThat(retention.purge()).isEqualTo(2);
        assertThat(remainingIds()).hasSize(8);
    }
}
