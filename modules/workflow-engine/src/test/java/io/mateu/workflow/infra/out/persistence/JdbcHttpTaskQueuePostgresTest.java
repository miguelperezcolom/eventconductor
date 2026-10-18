package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.httpworkers.HttpTask;
import io.mateu.workflow.application.httpworkers.HttpTaskQueue;
import io.mateu.workflow.application.httpworkers.HttpTaskQueueContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP worker queue on a real PostgreSQL (V50): the contract, and the property the whole design
 * rests on — concurrent activations, as several nodes would make them, take disjoint tasks through
 * {@code FOR UPDATE SKIP LOCKED} and none waits for another. Skipped without Docker.
 */
class JdbcHttpTaskQueuePostgresTest extends HttpTaskQueueContract {

    private final List<HikariDataSource> dataSources = new ArrayList<>();

    @Override
    protected HttpTaskQueue newQueue() {
        var ds = PostgresTestDatabase.freshMigratedDatabase();
        dataSources.add(ds);
        return queueOn(ds);
    }

    private static JdbcHttpTaskQueue queueOn(HikariDataSource ds) {
        return new JdbcHttpTaskQueue(new JdbcTemplate(ds), new PostgresDbLockDialect(),
                new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @AfterEach
    void close() {
        dataSources.forEach(HikariDataSource::close);
        dataSources.clear();
    }

    @Test
    void concurrentActivationsTakeDisjointTasks() throws Exception {
        var ds = dataSources.get(dataSources.size() - 1);
        int tasks = 400;
        for (int i = 0; i < tasks; i++) {
            queue.offer(task("t" + i, "payments", 1, T0.plusNanos(i * 1_000L)));
        }
        // Several "nodes": each its own queue object over the shared database, as each pod has.
        var nodes = List.of(queueOn(ds), queueOn(ds), queueOn(ds), queueOn(ds));
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var claimed = Collections.synchronizedList(new ArrayList<HttpTask>());
        var calls = new ArrayList<Callable<Void>>();
        for (int worker = 0; worker < 8; worker++) {
            var node = nodes.get(worker % nodes.size());
            var workerId = "w" + worker;
            calls.add(() -> {
                start.await();
                List<HttpTask> batch;
                do {
                    batch = node.claim(List.of("payments"), 7, workerId, T0.plusSeconds(1), T0.plusSeconds(61));
                    claimed.addAll(batch);
                } while (!batch.isEmpty());
                return null;
            });
        }
        var futures = calls.stream().map(pool::submit).toList();
        start.countDown();
        for (var future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        var ids = new HashSet<String>();
        claimed.forEach(task -> ids.add(task.id()));
        assertThat(claimed).hasSize(tasks);
        assertThat(ids).hasSize(tasks);
        assertThat(claimed).allSatisfy(task -> assertThat(task.deliveries()).isEqualTo(1));
        assertThat(claimed.stream().map(HttpTask::workerId).distinct().count()).isGreaterThan(1);
    }

    @Test
    void aClaimSkipsRowsAnotherTransactionHoldsInsteadOfWaiting() throws Exception {
        var ds = dataSources.get(dataSources.size() - 1);
        queue.offer(task("held", "payments", 1, T0));
        queue.offer(task("free", "payments", 1, T0.plusSeconds(1)));
        try (var con = ds.getConnection()) {
            con.setAutoCommit(false);
            try (var lock = con.prepareStatement("SELECT id FROM http_task WHERE id = 'held' FOR UPDATE")) {
                lock.executeQuery();
            }
            var started = System.nanoTime();
            var claimed = queue.claim(List.of("payments"), 5, "w1", T0.plusSeconds(2), T0.plusSeconds(62));
            assertThat(claimed).extracting(HttpTask::id).containsExactly("free");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
            con.rollback();
        }
    }
}
