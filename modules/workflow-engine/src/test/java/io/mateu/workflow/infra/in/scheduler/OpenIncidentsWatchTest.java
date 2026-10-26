package io.mateu.workflow.infra.in.scheduler;

import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenIncidentsWatchTest {

    @Test
    void aPassPublishesTheProcessesInErrorPerDefinitionAndActsOnNothing() {
        var processes = mock(ProcessRepository.class, CALLS_REAL_METHODS);
        doReturn(List.of(
                process("p1", "orders", ProcessStatus.ERROR),
                process("p2", "orders", ProcessStatus.ERROR),
                process("p3", "orders", ProcessStatus.RUNNING),
                process("p4", "refunds", ProcessStatus.ERROR),
                process("p5", "refunds", ProcessStatus.COMPENSATED),
                process("p6", "billing", ProcessStatus.COMPLETED))).when(processes).findAll();
        var metrics = mock(WorkflowMetrics.class);

        var returned = new OpenIncidentsWatch(processes, metrics).runOnce();

        verify(metrics).openIncidentsObserved(Map.of("orders", 2L, "refunds", 1L));
        assertThat(returned).as("a report, not work: a caller looping until idle must not be kept going").isZero();
    }

    @Test
    void theBackgroundWatchReportsAndSurvivesAFailingCount() {
        var processes = mock(ProcessRepository.class);
        when(processes.countByDefinitionWithStatus(ProcessStatus.ERROR))
                .thenThrow(new IllegalStateException("blip"))
                .thenReturn(Map.of("orders", 3L));
        var metrics = mock(WorkflowMetrics.class);
        var watch = new OpenIncidentsWatch(processes, metrics);
        watch.intervalMs = 20;
        watch.start();
        try {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    verify(metrics, atLeastOnce()).openIncidentsObserved(Map.of("orders", 3L)));
        } finally {
            watch.stop();
        }
    }

    @Test
    void aZeroIntervalNeverStartsTheWatch() {
        var processes = mock(ProcessRepository.class);
        var watch = new OpenIncidentsWatch(processes, mock(WorkflowMetrics.class));
        watch.intervalMs = 0;
        watch.start();
        watch.stop();
        org.mockito.Mockito.verifyNoInteractions(processes);
    }

    private static Process process(String id, String definition, ProcessStatus status) {
        return Process.builder().id(id).workflowDefinitionId(definition).status(status).build();
    }
}
