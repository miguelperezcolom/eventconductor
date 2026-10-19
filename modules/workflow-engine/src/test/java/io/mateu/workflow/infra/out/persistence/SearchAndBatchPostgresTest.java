package io.mateu.workflow.infra.out.persistence;

import com.zaxxer.hikari.HikariDataSource;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Search attributes and batch operations on PostgreSQL — and the runner's advisory lock, which only a
 * real PostgreSQL holds across sessions: while one pod works a page, another pod's tick does nothing.
 * Skipped without Docker.
 */
class SearchAndBatchPostgresTest extends SearchAndBatchContract {

    @Override
    DataSource newMigratedDatabase() {
        return PostgresTestDatabase.freshMigratedDatabase();
    }

    @Override
    void dispose(DataSource dataSource) {
        ((HikariDataSource) dataSource).close();
    }

    @Test
    void onlyOnePodRunsBatchesAtATime() throws Exception {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var busy = mock(BatchOperationService.class);
        when(busy.runPage()).thenAnswer(call -> {
            holding.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 3;
        });
        var other = mock(BatchOperationService.class);
        when(other.runPage()).thenReturn(1);

        var podA = job(busy);
        var podB = job(other);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var a = executor.submit(podA::runOnce);
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            // Pod A holds the lock: pod B's tick finds it taken and does nothing.
            assertThat(podB.runOnce()).isFalse();
            release.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
            // Released: pod B gets its turn.
            assertThat(podB.runOnce()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private io.mateu.workflow.infra.in.scheduler.BatchOperationJobAccess job(BatchOperationService service) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("jdbc", new JdbcTemplate(dataSource));
        beans.addBean("dialect", new PostgresDbLockDialect());
        return io.mateu.workflow.infra.in.scheduler.BatchOperationJobAccess.of(service, new BatchOperationProperties(),
                beans.getBeanProvider(JdbcTemplate.class), beans.getBeanProvider(DbLockDialect.class));
    }
}
