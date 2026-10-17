package io.mateu.workflow.e2e;

import io.mateu.workflow.application.out.RollupAnalyticsPort;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.e2e.support.AbstractJpaE2eTest;
import io.mateu.workflow.e2e.support.TestWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The analytics read model switched on the way a deployment switches it on
 * ({@code workflow.analytics.rollup=true}), in a booted engine: the context has to start with it,
 * the projector has to fold what the engine ran, and the report has to come back from the rollups
 * with the same numbers {@link AnalyticsJpaE2eTest} gets from the raw queries.
 *
 * <p>Until this test the flag had never been turned on in any test that boots the engine, and it
 * could not be: the read model's repositories were nested interfaces, which repository scanning
 * does not pick up, and its nested entities were named {@code AnalyticsRollupEntities$…} rather
 * than what its JPQL called them — so a context with the flag on failed to start.
 */
@TestPropertySource(properties = {
        "workflow.analytics.rollup=true",
        "workflow.analytics.rollup-interval-ms=100",
        "workflow.analytics.rollup-ceiling-seconds=0",
})
class AnalyticsRollupJpaE2eTest extends AbstractJpaE2eTest {

    @Autowired ProcessAnalyticsService analyticsService;
    @Autowired RollupAnalyticsPort rollup;

    @Test
    void theReportIsAnsweredFromTheRollupsOnceTheProjectorHasFolded() {
        worker.on("s1", TestWorker.succeed());
        worker.on("s2", TestWorker.succeed());
        worker.on("s3", TestWorker.succeed());

        createProcess("sequential-3", "ana-rollup-1");
        awaitStatus("ana-rollup-1", ProcessStatus.COMPLETED);
        createProcess("sequential-3", "ana-rollup-2");
        awaitStatus("ana-rollup-2", ProcessStatus.COMPLETED);

        // The projector folds in the background; once both finishes are in, the report agrees.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var analytics = analyticsService.analyze("sequential-3", TimeWindow.lastDays(1)).orElseThrow();
            assertThat(analytics.totalInstances()).isEqualTo(2);
            assertThat(analytics.instancesByStatus()).containsEntry(ProcessStatus.COMPLETED, 2L);
            assertThat(analytics.processDuration().samples()).isEqualTo(2);
            analytics.steps().stream()
                    .filter(step -> !"end".equals(step.stepId()))
                    .forEach(step -> assertThat(step.completed()).as("completed of " + step.stepId())
                            .isEqualTo(2));
        });

        // And it is the read model answering, not the raw GROUP BY behind it.
        var processes = rollup.aggregateProcesses(null, null);
        assertThat(processes.statusCounts()).anySatisfy(count -> {
            assertThat(count.workflowDefinitionId()).isEqualTo("sequential-3");
            assertThat(count.status()).isEqualTo(ProcessStatus.COMPLETED);
            assertThat(count.count()).isEqualTo(2);
        });
    }
}
