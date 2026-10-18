package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.ProcessRetentionPolicy;
import io.mateu.workflow.application.services.ProcessRetentionService;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.infra.config.ProcessRetentionProperties;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The purge runs on its interval, on one pod at a time where there is a database, and never stops for a failure. */
class ProcessRetentionJobTest {

    final ProcessRetentionService service = mock(ProcessRetentionService.class);
    final ProcessRetentionProperties properties = new ProcessRetentionProperties();
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();

    ProcessRetentionJob job() {
        when(service.policy()).thenReturn(ProcessRetentionPolicy.of(properties));
        return new ProcessRetentionJob(service, properties,
                beans.getBeanProvider(JdbcTemplate.class), beans.getBeanProvider(DbLockDialect.class));
    }

    static ProcessRetentionService.PurgeReport report(int purged, int kept) {
        return new ProcessRetentionService.PurgeReport(
                purged == 0 ? Map.of() : Map.of("orders", Map.of(ProcessStatus.COMPLETED, purged)), kept);
    }

    @Test
    void runsOnItsIntervalAndAFailedPassDoesNotStopIt() {
        properties.setRetention(Duration.ofDays(30));
        properties.getPurge().setInterval(Duration.ofMillis(20));
        when(service.purgeDue(any()))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(report(3, 1))
                .thenReturn(report(0, 0));
        var job = job();

        job.start();
        try {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(service, atLeast(3)).purgeDue(any()));
        } finally {
            job.stop();
        }
    }

    @Test
    void switchedOffItNeverRuns() throws Exception {
        properties.getPurge().setEnabled(false);
        properties.getPurge().setInterval(Duration.ofMillis(10));
        var job = job();
        job.start();
        Thread.sleep(100);
        job.stop();

        properties.getPurge().setEnabled(true);
        properties.getPurge().setInterval(Duration.ZERO);
        var zero = job();
        zero.start();
        Thread.sleep(50);
        zero.stop();

        verify(service, never()).purgeDue(any());
    }

    @Test
    void onADatabaseOnlyThePodHoldingTheLockPurges() throws Exception {
        var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
        try {
            var dialect = mock(DbLockDialect.class);
            beans.addBean("jdbc", new JdbcTemplate(database));
            beans.addBean("dialect", dialect);
            when(service.purgeDue(any())).thenReturn(report(1, 0));
            var job = job();

            when(dialect.tryLock(any(), anyLong())).thenReturn(false);
            job.purgeQuietly();
            verify(service, never()).purgeDue(any());

            when(dialect.tryLock(any(), anyLong())).thenReturn(true);
            job.purgeQuietly();
            verify(service).purgeDue(any());
            verify(dialect).unlock(any(), eq(ProcessRetentionJob.LOCK_ID));
        } finally {
            database.shutdown();
        }
    }

    @Test
    void aPassReportsWhatItDid() {
        when(service.purgeDue(any())).thenReturn(report(2, 1));
        assertThat(job().purgeOnce().total()).isEqualTo(2);
        when(service.purgeDue(any())).thenReturn(report(0, 0));
        assertThat(job().purgeOnce().total()).isZero();
    }
}
