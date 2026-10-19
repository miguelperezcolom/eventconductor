package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.infra.out.persistence.DbLockDialect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The batch runner as a scheduler pass: its background loop, and a test kit's runOnce. */
class BatchOperationJobTest {

    final BatchOperationService service = mock(BatchOperationService.class);
    final BatchOperationProperties properties = new BatchOperationProperties();

    BatchOperationJob job() {
        var beans = new StaticListableBeanFactory();
        return new BatchOperationJob(service, properties, beans.getBeanProvider(JdbcTemplate.class),
                beans.getBeanProvider(DbLockDialect.class));
    }

    @Test
    void inMemoryARunIsOnePageOnTheCallingThread() {
        when(service.runPage()).thenReturn(7);
        assertThat(job().runOnce()).isEqualTo(7);
        properties.setEnabled(false);
        assertThat(job().runOnce()).isZero();
    }

    @Test
    void theLoopRunsInTheBackgroundUnlessSchedulersAreForeground() throws Exception {
        properties.setPollInterval(Duration.ofMillis(20));
        var foreground = job();
        foreground.background = false;
        foreground.start();
        Thread.sleep(100);
        foreground.stop();
        verify(service, never()).runPage();

        var background = job();
        background.start();
        try {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(service, atLeastOnce()).runPage());
        } finally {
            background.stop();
        }
    }

    @Test
    void aFailedPageDoesNotStopTheLoop() {
        when(service.runPage()).thenThrow(new IllegalStateException("blip"));
        job().runQuietly();
        properties.setEnabled(false);
        var off = job();
        off.start();
        off.stop();
    }
}
