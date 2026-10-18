package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessPurgeRepository.PurgeCandidate;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retention purge's SQL against the migrated schema — what it deletes, what it keeps, and what
 * makes it refuse — run on H2 (every build) and on PostgreSQL (wherever Docker is).
 */
abstract class ProcessPurgeContract {

    static final LocalDateTime NOW = LocalDateTime.now().withNano(0);
    static final LocalDateTime LONG_AGO = NOW.minusDays(40);
    static final LocalDateTime CUTOFF = NOW.minusDays(30);

    /** Every table a purged process's rows live in, with the column that says whose they are. */
    static final List<String[]> OWNED = List.of(
            new String[]{"process_entity", "id"},
            new String[]{"step_execution_entity", "process_id"},
            new String[]{"log_message_entity", "process_id"},
            new String[]{"resource_entity", "process_id"},
            new String[]{"process_trace_context", "process_id"},
            new String[]{"sync_invocation", "process_id"},
            new String[]{"analytics_counted_process", "process_id"},
            new String[]{"process_index", "process_id"});

    DataSource dataSource;
    JdbcTemplate jdbc;
    JdbcProcessPurgeRepository repository;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void open() {
        dataSource = newMigratedDatabase();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcProcessPurgeRepository(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @AfterEach
    void close() {
        dispose(dataSource);
    }

    void process(String id, String definition, String status, LocalDateTime finished, String parentStepExecutionId) {
        jdbc.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, finished,"
                        + " parent_step_execution_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, "bk-" + id, status, definition, finished == null ? null : Timestamp.valueOf(finished),
                parentStepExecutionId);
    }

    String step(String processId, String stepId, String status) {
        var id = processId + "-" + stepId;
        jdbc.update("INSERT INTO step_execution_entity (id, process_id, step_id, status) VALUES (?, ?, ?, ?)",
                id, processId, stepId, status);
        return id;
    }

    /** A finished process with a row in every table that belongs to it. */
    void finished(String id, String definition, String status) {
        process(id, definition, status, LONG_AGO, null);
        var step = step(id, "a", "COMPLETED");
        jdbc.update("INSERT INTO log_message_entity (id, process_id, message) VALUES (?, ?, 'hi')", id + "-log", id);
        jdbc.update("INSERT INTO resource_entity (id, process_id, name) VALUES (?, ?, 'r')", id + "-res", id);
        jdbc.update("INSERT INTO process_trace_context (process_id, trace_parent) VALUES (?, '00-x-y-01')", id);
        jdbc.update("INSERT INTO sync_invocation (id, workflow_definition_id, idempotency_key, process_id) VALUES (?, ?, ?, ?)",
                id + "-inv", definition, "key-" + id, id);
        jdbc.update("INSERT INTO analytics_counted_process (process_id, workflow_definition_id, status) VALUES (?, ?, ?)",
                id, definition, status);
        jdbc.update("INSERT INTO analytics_counted_step (step_execution_id, workflow_definition_id, step_id, status)"
                + " VALUES (?, ?, 'a', 'COMPLETED')", step, definition);
        jdbc.update("INSERT INTO process_index (process_id, business_key, workflow_definition_id, status) VALUES (?, ?, ?, ?)",
                id, "bk-" + id, definition, status);
        jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type, payload, partition_key)"
                + " VALUES (?, ?, 'Sent', 'x', '{}', ?)", id + "-out", Timestamp.valueOf(LONG_AGO), id);
    }

    int rowsOf(String id) {
        var total = 0;
        for (var table : OWNED) {
            total += jdbc.queryForObject("SELECT COUNT(*) FROM " + table[0] + " WHERE " + table[1] + " = ?", Integer.class, id);
        }
        return total + jdbc.queryForObject("SELECT COUNT(*) FROM analytics_counted_step WHERE step_execution_id = ?",
                Integer.class, id + "-a");
    }

    List<PurgeCandidate> roots(String definition, ProcessStatus status) {
        return repository.findRoots(definition, status, CUTOFF, null, 100);
    }

    @Test
    void purgesTheTreeAndEveryRowThatBelongsToItKeepingTheRollupsAndTheOutbox() {
        finished("old", "orders", "COMPLETED");
        finished("other", "orders", "COMPLETED");
        jdbc.update("INSERT INTO process_status_daily (k, workflow_definition_id, created_day, status, cnt)"
                + " VALUES ('orders|COMPLETED', 'orders', CURRENT_DATE, 'COMPLETED', 2)");
        assertThat(rowsOf("old")).isEqualTo(9);

        var found = roots("orders", ProcessStatus.COMPLETED);
        assertThat(found).extracting(PurgeCandidate::id).containsExactly("old", "other");
        assertThat(repository.purge(List.of(found.getFirst()))).isTrue();

        assertThat(rowsOf("old")).isZero();
        assertThat(rowsOf("other")).isEqualTo(9);
        // Kept: the daily rollups, and the sent outbox rows (the outbox's own retention removes them).
        assertThat(jdbc.queryForObject("SELECT cnt FROM process_status_daily", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message_entity WHERE partition_key = 'old'",
                Integer.class)).isEqualTo(1);
        // The business key is free again.
        process("again", "orders", "RUNNING", null, null);
        jdbc.update("UPDATE process_entity SET business_key = 'bk-old' WHERE id = 'again'");
    }

    @Test
    void findsOnlyDueRootsOfThatDefinitionAndStatusOldestFirstAndPages() {
        process("a", "orders", "ERROR", LONG_AGO.minusDays(1), null);
        process("b", "orders", "ERROR", LONG_AGO, null);
        process("c", "orders", "ERROR", LONG_AGO, null);
        process("recent", "orders", "ERROR", NOW.minusDays(1), null);
        process("completed", "orders", "COMPLETED", LONG_AGO, null);
        process("elsewhere", "billing", "ERROR", LONG_AGO, null);
        process("running", "orders", "RUNNING", null, null);
        // A child whose parent is still there is not a root; one whose parent is gone is.
        var call = step("running", "call", "COMPLETED");
        process("child", "orders", "ERROR", LONG_AGO, call);
        process("orphan", "orders", "ERROR", LONG_AGO, "no-such-step");

        assertThat(roots("orders", ProcessStatus.ERROR)).extracting(PurgeCandidate::id)
                .containsExactly("a", "b", "c", "orphan");
        var first = repository.findRoots("orders", ProcessStatus.ERROR, CUTOFF, null, 2);
        assertThat(first).extracting(PurgeCandidate::id).containsExactly("a", "b");
        assertThat(repository.findRoots("orders", ProcessStatus.ERROR, CUTOFF, first.getLast(), 2))
                .extracting(PurgeCandidate::id).containsExactly("c", "orphan");
        assertThat(first.getFirst().finished()).isEqualTo(LONG_AGO.minusDays(1));
        assertThat(first.getFirst().status()).isEqualTo(ProcessStatus.ERROR);
        assertThat(first.getFirst().workflowDefinitionId()).isEqualTo("orders");

        assertThat(repository.findChildren(List.of("running"))).extracting(PurgeCandidate::id).containsExactly("child");
        assertThat(repository.findChildren(List.of("a", "b"))).isEmpty();
    }

    @Test
    void refusesWhenAnyProcessOfTheTreeIsNoLongerTerminalAndDeletesNothing() {
        finished("root", "orders", "COMPLETED");
        var call = step("root", "call", "COMPLETED");
        process("child", "orders", "COMPLETED", LONG_AGO, call);
        var tree = List.of(new PurgeCandidate("root", "orders", ProcessStatus.COMPLETED, LONG_AGO),
                new PurgeCandidate("child", "orders", ProcessStatus.COMPLETED, LONG_AGO));
        // An operator retried the child after the purge found the tree.
        jdbc.update("UPDATE process_entity SET status = 'RUNNING', finished = NULL WHERE id = 'child'");
        var before = rowsOf("root");

        assertThat(repository.purge(tree)).isFalse();
        assertThat(rowsOf("root")).isEqualTo(before).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity", Integer.class)).isEqualTo(2);

        jdbc.update("UPDATE process_entity SET status = 'COMPLETED', finished = ? WHERE id = 'child'", Timestamp.valueOf(LONG_AGO));
        assertThat(repository.purge(tree)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity", Integer.class)).isZero();
    }

    @Test
    void refusesWhileAStepIsAtWorkOrWaitingToRetryALockIsHeldOrAwaitedOrAMessageIsUnsent() {
        for (var blocker : List.of("AWAITING_RETRY", "RUNNING", "PENDING", "WAITING_ON_LOCK", "holder", "waiter", "outbox")) {
            var id = "p-" + blocker;
            finished(id, "orders", "ERROR");
            switch (blocker) {
                case "holder" -> jdbc.update("INSERT INTO process_lock (id, lock_name, lock_key, holder_process_id)"
                        + " VALUES ('l', 'booking', '42', ?)", id);
                case "waiter" -> jdbc.update("INSERT INTO process_lock_waiter (id, lock_name, lock_key, process_id, enqueued_at)"
                        + " VALUES ('w', 'booking', '42', ?, ?)", id, Timestamp.valueOf(NOW));
                case "outbox" -> jdbc.update("INSERT INTO outbox_message_entity (id, timestamp, status, message_type,"
                        + " payload, partition_key) VALUES ('pending', ?, 'Pending', 'x', '{}', ?)", Timestamp.valueOf(NOW), id);
                default -> step(id, "b", blocker);
            }
            var tree = List.of(new PurgeCandidate(id, "orders", ProcessStatus.ERROR, LONG_AGO));
            var before = rowsOf(id);
            assertThat(repository.purge(tree)).as(blocker).isFalse();
            assertThat(rowsOf(id)).as(blocker).isEqualTo(before).isGreaterThanOrEqualTo(9);
        }
        assertThat(repository.purge(List.of())).isFalse();
    }

    @Test
    void knowsTheDefinitionsThatProcessesMayBelongToFromTheVersionHistory() {
        jdbc.update("INSERT INTO workflow_definition_version (id, definition_id, version, content_hash, created_at)"
                + " VALUES ('gone:1', 'gone', 1, 'h', ?)", Timestamp.valueOf(NOW));
        jdbc.update("INSERT INTO workflow_definition_version (id, definition_id, version, content_hash, created_at)"
                + " VALUES ('gone:2', 'gone', 2, 'h', ?)", Timestamp.valueOf(NOW));
        assertThat(repository.knownWorkflowDefinitionIds()).containsExactly("gone");
    }

    /**
     * Before 2.28 a cancelled process had no end time, so nothing counted from it: V45 backfills one
     * from the process's steps (or its own start/creation) and the old process becomes purgeable.
     */
    @Test
    void v45BackfillsTheEndOfTerminalProcessesThatHaveNoneSoTheyArePurged() {
        var legacy = newDatabaseMigratedTo("44");
        try {
            var old = new JdbcTemplate(legacy);
            old.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, created, started)"
                    + " VALUES ('cancelled', 'bk-c', 'CANCELLED', 'orders', ?, ?)",
                    Timestamp.valueOf(LONG_AGO.minusDays(2)), Timestamp.valueOf(LONG_AGO.minusDays(2)));
            old.update("INSERT INTO step_execution_entity (id, process_id, step_id, status, started_at, finished_at)"
                    + " VALUES ('c-a', 'cancelled', 'a', 'CANCELLED', ?, ?)",
                    Timestamp.valueOf(LONG_AGO.minusDays(1)), Timestamp.valueOf(LONG_AGO));
            old.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, created)"
                    + " VALUES ('errored', 'bk-e', 'ERROR', 'orders', ?)", Timestamp.valueOf(LONG_AGO));
            old.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, created)"
                    + " VALUES ('running', 'bk-r', 'RUNNING', 'orders', ?)", Timestamp.valueOf(LONG_AGO));

            PostgresTestDatabase.migrate(legacy);

            assertThat(old.queryForObject("SELECT finished FROM process_entity WHERE id = 'cancelled'", Timestamp.class)
                    .toLocalDateTime()).isEqualTo(LONG_AGO);   // its last step's end
            assertThat(old.queryForObject("SELECT finished FROM process_entity WHERE id = 'errored'", Timestamp.class)
                    .toLocalDateTime()).isEqualTo(LONG_AGO);   // no steps: its creation
            assertThat(old.queryForObject("SELECT finished FROM process_entity WHERE id = 'running'", Timestamp.class))
                    .isNull();                                 // not ended: untouched

            var purge = new JdbcProcessPurgeRepository(old,
                    new TransactionTemplate(new DataSourceTransactionManager(legacy)));
            var due = purge.findRoots("orders", ProcessStatus.CANCELLED, CUTOFF, null, 10);
            assertThat(due).extracting(PurgeCandidate::id).containsExactly("cancelled");
            assertThat(purge.purge(due)).isTrue();
            assertThat(old.queryForObject("SELECT COUNT(*) FROM process_entity WHERE id = 'cancelled'", Integer.class)).isZero();
        } finally {
            dispose(legacy);
        }
    }

    /** A database with the engine's migrations applied up to {@code version}, as an older release left it. */
    abstract DataSource newDatabaseMigratedTo(String version);
}
