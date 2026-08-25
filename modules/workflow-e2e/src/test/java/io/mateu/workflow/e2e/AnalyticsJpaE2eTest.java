package io.mateu.workflow.e2e;

import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analytics over a real database. {@link AnalyticsE2eTest} covers the numbers in memory mode; what
 * this adds is the JPA path, where the snapshot comes from two projection queries written in JPQL
 * — including a join from step executions to their process, and a property named {@code order}
 * that the query language also uses as a keyword. None of that is checked at compile time.
 *
 * <p>The page used to build this snapshot by loading every process and then re-reading the entire
 * step-execution table once per workflow definition. On the demo deployment that was around 2.7 GB
 * of row data for one page: it took a minute, returned a 500, and took the pod down with it.
 */
class AnalyticsJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessAnalyticsService analyticsService;

    @Test
    void computesTheSameReportFromProjectionQueries() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "ana-jpa-1");
        awaitStatus("ana-jpa-1", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "ana-jpa-2");
        awaitStatus("ana-jpa-2", ProcessStatus.COMPLETED);

        var analytics = analyticsService.analyze("sequential-3", TimeWindow.lastDays(1)).orElseThrow();

        assertThat(analytics.workflowDefinitionName()).isEqualTo("Sequential 3 steps");
        assertThat(analytics.totalInstances()).isEqualTo(2);
        assertThat(analytics.instancesByStatus()).containsEntry(ProcessStatus.COMPLETED, 2L);
        assertThat(analytics.completionRatePct()).isEqualTo(100.0);
        assertThat(analytics.createdPerDay().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(2);
        assertThat(analytics.processDuration().samples()).isEqualTo(2);

        // The steps come back joined to their processes, in flow order, with their durations.
        assertThat(analytics.steps()).isNotEmpty();
        analytics.steps().stream()
                .filter(step -> !"end".equals(step.stepId()))
                .forEach(step -> {
                    assertThat(step.executions()).as("executions of " + step.stepId()).isEqualTo(2);
                    assertThat(step.completed()).as("completed of " + step.stepId()).isEqualTo(2);
                });
        assertThat(analytics.steps().stream().filter(step -> step.bottleneck()).count()).isEqualTo(1);
        assertThat(analytics.bottleneckStepId()).isNotNull();
    }

    @Test
    void leavesOutWhatTheWindowExcludes() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "ana-jpa-win");
        awaitStatus("ana-jpa-win", ProcessStatus.COMPLETED);

        // The window is pushed into the query now, so a window that ends before anything was
        // created has to come back empty rather than merely being filtered afterwards.
        var past = new TimeWindow(null, java.time.LocalDateTime.now().minusDays(1));
        var analytics = analyticsService.analyze("sequential-3", past).orElseThrow();

        assertThat(analytics.totalInstances()).isZero();
        assertThat(analytics.steps()).isEmpty();
        assertThat(analytics.processDuration().samples()).isZero();
    }
}
