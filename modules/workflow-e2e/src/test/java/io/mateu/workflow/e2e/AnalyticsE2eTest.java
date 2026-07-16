package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E-ANA-01: built-in analytics reflect executed processes.
 *
 * <p>Runs in a fresh context: analytics count every process of a definition in the shared
 * repositories, and other memory-mode tests also create `sequential-3` processes, so this
 * test must start from empty repositories to assert exact instance counts (order-independent).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AnalyticsE2eTest extends AbstractE2eTest {

    @Autowired ProcessAnalyticsService analyticsService;

    @Test
    void analyticsReflectExecutedProcesses() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());
        createProcess("sequential-3", "ana-ok-1");
        createProcess("sequential-3", "ana-ok-2");
        assertThat(process("ana-ok-1").getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(process("ana-ok-2").getStatus()).isEqualTo(ProcessStatus.COMPLETED);

        worker.on("flaky", TestWorker.fail());
        createProcess("retry", "ana-ko-1");
        assertThat(process("ana-ko-1").getStatus()).isEqualTo(ProcessStatus.ERROR);

        var analytics = analyticsService.analyze("sequential-3", TimeWindow.lastDays(1)).orElseThrow();
        assertThat(analytics.workflowDefinitionName()).isEqualTo("Sequential 3 steps");
        assertThat(analytics.totalInstances()).isEqualTo(2);
        assertThat(analytics.instancesByStatus()).containsEntry(ProcessStatus.COMPLETED, 2L);
        assertThat(analytics.completionRatePct()).isEqualTo(100.0);
        assertThat(analytics.errorRatePct()).isZero();
        assertThat(analytics.createdPerDay().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2);
        // Every step ran twice and carries measured durations (finishedAt is stamped on completion).
        assertThat(analytics.steps()).isNotEmpty();
        analytics.steps().stream()
                .filter(step -> !"end".equals(step.stepId()))
                .forEach(step -> {
                    assertThat(step.executions()).as("executions of " + step.stepId()).isEqualTo(2);
                    assertThat(step.completed()).as("completed of " + step.stepId()).isEqualTo(2);
                    assertThat(step.duration().samples()).as("duration samples of " + step.stepId()).isEqualTo(2);
                });
        // Exactly one step is flagged as the bottleneck.
        assertThat(analytics.steps().stream().filter(step -> step.bottleneck()).count()).isEqualTo(1);
        assertThat(analytics.bottleneckStepId()).isNotNull();

        // The failing definition shows up with its error rate.
        var failing = analyticsService.analyze("retry", TimeWindow.lastDays(1)).orElseThrow();
        assertThat(failing.totalInstances()).isEqualTo(1);
        assertThat(failing.instancesByStatus()).containsEntry(ProcessStatus.ERROR, 1L);
        assertThat(failing.errorRatePct()).isEqualTo(100.0);
        assertThat(failing.steps().stream().filter(step -> step.failed() > 0)).isNotEmpty();
    }
}
