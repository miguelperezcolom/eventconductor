package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.ProcessUpdateRepository;
import io.mateu.workflow.application.updates.ProcessUpdate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The process-update store on a database the engine's own migrations created (V55): the unique key that
 * is the idempotency, the outcome written in the caller's transaction, the waiters' query, the key expiry,
 * the definition's {@code updates_json} column.
 */
abstract class ProcessUpdateStoreContract {

    static final LocalDateTime AT = LocalDateTime.of(2026, 10, 19, 9, 0);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    JpaProcessUpdateRepository store;
    TransactionTemplate tx;

    abstract DataSource newMigratedDatabase();

    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void openContext() {
        dataSource = newMigratedDatabase();
        context = AnalyticsJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        store = new JpaProcessUpdateRepository(context.getBean(ProcessUpdateEntityRepository.class), tx);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        if (dataSource != null) {
            dispose(dataSource);
        }
    }

    static ProcessUpdate requested(String id, String processId, String key, LocalDateTime requestedAt) {
        return new ProcessUpdate(id, processId, "orders", "rename", key, "hash", "{\"name\":\"****\"}",
                ProcessUpdate.Status.REQUESTED, null, null, "ana", false, requestedAt, null, requestedAt.plusHours(24));
    }

    @Test
    void anUpdateIsRecordedAndFinishedInTheCallersTransaction() {
        store.insert(requested("u-1", "p-1", "key-1", AT));

        tx.executeWithoutResult(status -> store.save(store.findById("u-1").orElseThrow()
                .finished(ProcessUpdate.Status.COMPLETED, "{\"customer\":\"Ana\"}", null, AT.plusSeconds(1))));

        var read = store.findById("u-1").orElseThrow();
        assertThat(read.status()).isEqualTo(ProcessUpdate.Status.COMPLETED);
        assertThat(read.result()).isEqualTo("{\"customer\":\"Ana\"}");
        assertThat(read.payload()).isEqualTo("{\"name\":\"****\"}");
        assertThat(read.requestedBy()).isEqualTo("ana");
        assertThat(store.findByKey("p-1", "key-1")).contains(read);
        assertThat(store.findFinishedAmong(List.of("u-1", "nope"))).containsExactly(read);
        assertThat(store.findFinishedAmong(List.of())).isEmpty();
    }

    @Test
    void anOutcomeRolledBackWithItsTransactionLeavesTheUpdateRequested() {
        store.insert(requested("u-1", "p-1", "key-1", AT));

        try {
            tx.executeWithoutResult(status -> {
                store.save(store.findById("u-1").orElseThrow().finished(ProcessUpdate.Status.COMPLETED, "1", null, AT));
                throw new IllegalStateException("the process save failed");
            });
        } catch (IllegalStateException expected) {
            // the outcome goes with the change it describes
        }

        assertThat(store.findById("u-1").orElseThrow().status()).isEqualTo(ProcessUpdate.Status.REQUESTED);
        assertThat(store.findFinishedAmong(List.of("u-1"))).isEmpty();
    }

    @Test
    void theKeyIsTheIdempotencyEvenUnderConcurrency() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        var calls = new java.util.ArrayList<Callable<Boolean>>();
        for (int i = 0; i < 6; i++) {
            var id = "c-" + i;
            calls.add(() -> {
                try {
                    store.insert(requested(id, "p-1", "same-key", AT));
                    return true;
                } catch (ProcessUpdateRepository.DuplicateUpdateException e) {
                    return false;
                }
            });
        }
        int won = 0;
        try {
            for (var future : pool.invokeAll(calls)) {
                won += future.get() ? 1 : 0;
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(won).isEqualTo(1);
        assertThat(store.findByProcessId("p-1")).hasSize(1);
        // The same key on another process is another update.
        store.insert(requested("other", "p-2", "same-key", AT));
        assertThatThrownBy(() -> store.inTransaction(() -> store.insert(requested("again", "p-2", "same-key", AT))))
                .isInstanceOf(ProcessUpdateRepository.DuplicateUpdateException.class);
    }

    @Test
    void expiredKeysAreFreedAndTheRecordsKept() {
        store.insert(requested("old", "p-1", "key", AT.minusDays(2)));
        store.insert(requested("new", "p-1", "fresh", AT));

        assertThat(store.expireKeys(AT)).isEqualTo(1);

        assertThat(store.findByKey("p-1", "key")).isEmpty();
        assertThat(store.findByKey("p-1", "old")).isPresent();
        assertThat(store.findById("old").orElseThrow().expiresAt()).isNull();
        store.insert(requested("reused", "p-1", "key", AT));
        assertThat(store.findByProcessId("p-1")).extracting(ProcessUpdate::id).containsExactly("old", "new", "reused");
    }

    @Test
    void thePurgeDeletesAProcesssUpdates() {
        store.insert(requested("a", "p-1", "a", AT));
        store.insert(requested("b", "p-2", "b", AT));

        assertThat(store.deleteByProcessIds(List.of("p-1"))).isEqualTo(1);
        assertThat(store.deleteByProcessIds(List.of())).isZero();
        assertThat(store.findById("a")).isEmpty();
        assertThat(store.findById("b")).isPresent();
    }

    @Test
    void theDefinitionKeepsItsUpdatesAsJson() {
        jdbc.update("INSERT INTO workflow_definition_entity (id, name, version, updates_json) VALUES ('orders', 'Orders', 1, ?)",
                "[{\"name\":\"rename\"}]");

        assertThat(jdbc.queryForObject("SELECT updates_json FROM workflow_definition_entity WHERE id = 'orders'",
                String.class)).isEqualTo("[{\"name\":\"rename\"}]");
        assertThat(WorkflowDefinitionDBRepository.updatesFromJson("[{\"name\":\"rename\",\"reply\":\"1\"}]"))
                .singleElement().satisfies(update -> assertThat(update.reply()).isEqualTo("1"));
        assertThat(WorkflowDefinitionDBRepository.updatesFromJson("not json")).isNull();
        assertThat(WorkflowDefinitionDBRepository.updatesToJson(null)).isNull();
    }
}
