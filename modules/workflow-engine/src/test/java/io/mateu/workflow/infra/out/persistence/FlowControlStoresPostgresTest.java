package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.flowcontrol.FlowControlService;
import io.mateu.workflow.application.flowcontrol.FlowControlStore;
import io.mateu.workflow.application.flowcontrol.FlowStart;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.IngressRouter;
import io.mateu.workflow.application.usecases.process.cancel.CancelProcessUseCase;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StartPolicy;
import io.mateu.workflow.security.AuthorizationContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The V56 stores on PostgreSQL, and flow control under concurrency: two engines — two services, each with its
 * own connection pool on the same database, as two pods would be — admitting starts for one key at once. The
 * row lock on the key is what keeps them from both seeing room in the window, or both writing a debounce.
 */
class FlowControlStoresPostgresTest extends FlowControlStoresContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }

    /** One "pod": its own pool on the shared database, its own store and service. */
    record Engine(HikariDataSource pool, FlowControlService service) {
    }

    @SuppressWarnings("unchecked")
    Engine engine(ProcessRepository processes) {
        var pool = new HikariDataSource();
        var shared = (HikariDataSource) dataSource;
        pool.setJdbcUrl(shared.getJdbcUrl());
        pool.setUsername(shared.getUsername());
        pool.setPassword(shared.getPassword());
        pool.setMaximumPoolSize(12);
        var store = new JdbcFlowControlStore(new JdbcTemplate(pool), new DataSourceTransactionManager(pool));
        ObjectProvider<CancelProcessUseCase> cancel = mock(ObjectProvider.class);
        when(cancel.getObject()).thenReturn(mock(CancelProcessUseCase.class));
        ObjectProvider<IngressRouter> router = mock(ObjectProvider.class);
        when(router.getObject()).thenReturn(mock(IngressRouter.class));
        return new Engine(pool, new FlowControlService(store, processes, cancel, router, mock(WorkflowMetrics.class)));
    }

    static FlowStart start(StartPolicy policy, String businessKey) {
        return new FlowStart(FlowStart.startScope("wf"), "customer-1", "wf", businessKey, Map.of("n", businessKey),
                AuthorizationContext.SYSTEM, FlowStart.START, null, policy, false);
    }

    /** Runs {@code perEngine} admissions on each of two engines, all released at once. */
    List<FlowControlService.Decision> race(StartPolicy policy, int perEngine, ProcessRepository processes) throws Exception {
        var engines = List.of(engine(processes), engine(processes));
        var pool = Executors.newFixedThreadPool(perEngine * 2);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<FlowControlService.Decision>>();
        try {
            for (var engine : engines) {
                for (int i = 0; i < perEngine; i++) {
                    var businessKey = "bk-" + futures.size();
                    futures.add(pool.submit(() -> {
                        go.await();
                        return engine.service().admit(start(policy, businessKey),
                                s -> new FlowControlService.Started("p-" + s.businessKey(), s.businessKey()),
                                FlowControlService.Mode.DEFAULT);
                    }));
                }
            }
            go.countDown();
            var decisions = new ArrayList<FlowControlService.Decision>();
            for (var future : futures) {
                decisions.add(future.get(60, TimeUnit.SECONDS));
            }
            return decisions;
        } finally {
            pool.shutdownNow();
            engines.forEach(engine -> engine.pool().close());
        }
    }

    static long count(List<FlowControlService.Decision> decisions, FlowControlService.Outcome outcome) {
        return decisions.stream().filter(d -> d.outcome() == outcome).count();
    }

    @Test
    void twoEnginesNeverAdmitMoreThanTheThrottleAllowsAndQueueTheRestInOneLine() throws Exception {
        var policy = new StartPolicy("${n}", null, new StartPolicy.Throttle(5, "PT1H", null), null);

        var decisions = race(policy, 12, mock(ProcessRepository.class));

        assertThat(count(decisions, FlowControlService.Outcome.STARTED)).isEqualTo(5);
        assertThat(count(decisions, FlowControlService.Outcome.THROTTLED)).isEqualTo(19);
        assertThat(flow.pending(FlowControlStore.Kind.THROTTLE)).isEqualTo(19);
        var keyId = FlowControlService.keyId(FlowStart.startScope("wf"), "customer-1");
        flow.withKey(keyId, null, null, tx -> {
            assertThat(tx.state().windowCount()).isEqualTo(5);
            var ids = tx.queued().stream().map(FlowControlStore.Pending::id).toList();
            assertThat(ids).doesNotHaveDuplicates().hasSize(19).isSorted();
            assertThat(ids.getLast()).endsWith(String.format("%019d", 18));
            return null;
        });
    }

    @Test
    void twoEnginesDebouncingOneKeyKeepOneWaitingStart() throws Exception {
        var policy = new StartPolicy("${n}", "PT30S", null, null);

        var decisions = race(policy, 10, mock(ProcessRepository.class));

        assertThat(count(decisions, FlowControlService.Outcome.DEBOUNCED)).isEqualTo(20);
        assertThat(flow.pending(FlowControlStore.Kind.DEBOUNCE)).isEqualTo(1);
        var keyId = FlowControlService.keyId(FlowStart.startScope("wf"), "customer-1");
        flow.withKey(keyId, null, null, tx -> {
            assertThat(tx.debounce()).hasValueSatisfying(p -> assertThat(p.absorbed()).isEqualTo(20));
            return null;
        });
    }

    @Test
    void twoEnginesStartOneSingleton() throws Exception {
        var processes = mock(ProcessRepository.class);
        when(processes.findById(anyString())).thenAnswer(inv -> Optional.of(Process.builder()
                .id(inv.getArgument(0)).status(ProcessStatus.RUNNING).build()));
        var policy = new StartPolicy(null, null, null, "skip");

        var decisions = race(policy, 8, processes);

        assertThat(count(decisions, FlowControlService.Outcome.STARTED)).isEqualTo(1);
        assertThat(count(decisions, FlowControlService.Outcome.SKIPPED)).isEqualTo(15);
    }

    /**
     * The rolling upgrade: a 2.32 engine — which knows nothing of released keys and relies on the UNIQUE constraint
     * to fail a duplicate creation — creates a process with a key while a 2.33 engine hands the same key over from
     * the run holding it (terminateRunning: the holder cancelled, its key released, the new run inserted, one
     * transaction). Whatever the interleaving, the old engine's insert fails on the constraint and exactly one
     * RUNNING process carries the key.
     */
    @Test
    void anOldEngineCreatingAKeyWhileANewOneHandsItOverNeverLeavesTwoRunningProcesses() throws Exception {
        var oldPool = engine(mock(ProcessRepository.class)).pool();
        var newPool = engine(mock(ProcessRepository.class)).pool();
        var oldJdbc = new JdbcTemplate(oldPool);
        var newTx = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(newPool));
        var newJdbc = new JdbcTemplate(newPool);
        var executor = Executors.newFixedThreadPool(2);
        int oldWins = 0;
        int newWins = 0;
        try {
            for (int round = 0; round < 40; round++) {
                var key = "mixed-" + round;
                insertProcess("holder-" + round, key);
                var go = new CountDownLatch(1);
                var holder = "holder-" + round;
                var successor = "new-" + round;
                var stale = "old-" + round;
                Future<Boolean> newer = executor.submit(() -> {
                    go.await();
                    try {
                        return Boolean.TRUE.equals(newTx.execute(status -> {
                            var version = newJdbc.queryForObject("SELECT COALESCE(version, 0) FROM process_entity WHERE id = ?",
                                    Integer.class, holder);
                            var released = newJdbc.update("UPDATE process_entity SET status = 'CANCELLED', business_key = NULL,"
                                    + " released_business_key = ?, version = ? WHERE id = ? AND COALESCE(version, 0) = ?"
                                    + " AND business_key = ?", key, version + 1, holder, version, key);
                            if (released != 1) {
                                status.setRollbackOnly();
                                return false;
                            }
                            newJdbc.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id,"
                                    + " created, continued_from_process_id) VALUES (?, ?, 'RUNNING', 'wf', ?, NULL)",
                                    successor, key, java.sql.Timestamp.valueOf(T0));
                            return true;
                        }));
                    } catch (org.springframework.dao.DataAccessException e) {
                        return false;
                    }
                });
                Future<Boolean> older = executor.submit(() -> {
                    go.await();
                    try {
                        // 2.32's create: check-then-insert, the constraint its backstop for the window in between.
                        oldJdbc.update("INSERT INTO process_entity (id, business_key, status, workflow_definition_id, created)"
                                + " VALUES (?, ?, 'RUNNING', 'wf', ?)", stale, key, java.sql.Timestamp.valueOf(T0));
                        return true;
                    } catch (org.springframework.dao.DataAccessException e) {
                        return false;
                    }
                });
                go.countDown();
                boolean oldCreated = older.get(60, TimeUnit.SECONDS);
                boolean newCreated = newer.get(60, TimeUnit.SECONDS);
                oldWins += oldCreated ? 1 : 0;
                newWins += newCreated ? 1 : 0;
                assertThat(oldCreated && newCreated).as("round " + round).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity WHERE status = 'RUNNING'"
                        + " AND (business_key = ? OR released_business_key = ?)", Integer.class, key, key))
                        .as("running processes with key " + key).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_entity WHERE business_key = ?",
                        Integer.class, key)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            oldPool.close();
            newPool.close();
        }
        // The old engine never gets a duplicate in; the new one hands the key over every time it is not refused.
        assertThat(oldWins).isZero();
        assertThat(newWins).isEqualTo(40);
    }

    /**
     * V56.1 under a Flyway configured with PostgreSQL's default transactional lock (not the engine's): the concurrent
     * build cannot finish while Flyway's own transaction is open, so it gives up after its wait and builds plainly —
     * the migration completes, the indexes are valid.
     */
    @Test
    void theIndexesAreBuiltEvenUnderAFlywayHoldingATransactionalLock() throws Exception {
        var previous = System.getProperty("eventconductor.migration.concurrent-index-wait-seconds");
        System.setProperty("eventconductor.migration.concurrent-index-wait-seconds", "2");
        var database = PostgresTestDatabase.freshDatabase();
        try {
            org.flywaydb.core.Flyway.configure().dataSource(database).locations("classpath:db/migration/workflow")
                    .table("workflow_schema_history").load().migrate();
            var indexes = new JdbcTemplate(database).queryForList("SELECT c.relname FROM pg_class c JOIN pg_index i"
                    + " ON i.indexrelid = c.oid WHERE i.indisvalid AND c.relname IN"
                    + " ('idx_process_released_business_key', 'idx_http_task_topic_priority')", String.class);
            assertThat(indexes).containsExactlyInAnyOrder("idx_process_released_business_key", "idx_http_task_topic_priority");
        } finally {
            database.close();
            if (previous == null) {
                System.clearProperty("eventconductor.migration.concurrent-index-wait-seconds");
            } else {
                System.setProperty("eventconductor.migration.concurrent-index-wait-seconds", previous);
            }
        }
    }

    @Test
    void twoEnginesTakeNoMoreRatePermitsThanTheWindowHas() throws Exception {
        var pool = Executors.newFixedThreadPool(16);
        var engines = List.of(engine(mock(ProcessRepository.class)), engine(mock(ProcessRepository.class)));
        var stores = engines.stream().map(e -> new JdbcRateLimitStore(new JdbcTemplate(e.pool()),
                new DataSourceTransactionManager(e.pool()))).toList();
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try {
            for (int i = 0; i < 32; i++) {
                var store = stores.get(i % 2);
                futures.add(pool.submit(() -> {
                    go.await();
                    return store.tryAcquire("partner", 7, Duration.ofHours(1), T0);
                }));
            }
            go.countDown();
            long granted = 0;
            for (var future : futures) {
                granted += future.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(granted).isEqualTo(7);
        } finally {
            pool.shutdownNow();
            engines.forEach(e -> e.pool().close());
        }
    }
}
