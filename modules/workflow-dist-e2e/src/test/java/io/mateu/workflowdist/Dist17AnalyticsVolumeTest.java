package io.mateu.workflowdist;

import io.mateu.workflow.application.out.ProcessAnalyticsRow;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionAnalyticsRow;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.services.ProcessAnalyticsService;
import io.mateu.workflow.application.services.ProcessAnalyticsService.TimeWindow;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.out.persistence.ProcessEntity;
import io.mateu.workflow.infra.out.persistence.ProcessEntityRepository;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntity;
import io.mateu.workflow.infra.out.persistence.StepExecutionEntityRepository;
import io.mateu.workflowdist.support.AbstractDistTest;
import io.mateu.workflowdist.support.DistInfra;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DIST-17 — The analytics report is reduced by the database, not carried into the JVM.
 *
 * <p>{@code /workflow/analytics} did not return. The request thread entered the route and never
 * logged again; the pod missed three liveness probes and was SIGKILLed. It was never the database —
 * PostgreSQL did the whole join in 247 ms — it was 383 215 projections materialised on the request
 * thread to put about fifty rows on a page.
 *
 * <p>So this test is about <b>what crosses the wire</b>, and it asserts that in the only way that
 * cannot be argued with: it seeds enough rows that folding them in the JVM is plainly slower, then
 * runs both reductions over the same data and requires the SQL one to be faster by a wide margin
 * and to finish inside a bound the old one cannot meet. Both numbers are printed, because the
 * ratio is the point and a bound on its own tells you nothing about why it passed.
 *
 * <p>The seed is smaller than the deployment that prompted this — a Testcontainers PostgreSQL on a
 * CI runner is not a four-node cluster, and a test that takes minutes gets deleted. It does not
 * need to reproduce the outage: it needs to fail if per-row data is materialised again, and the
 * margin at this size is already wide enough to do that.
 *
 * <p><b>Verified to discriminate.</b> With the two SQL overrides removed, so the service falls back
 * to the row-by-row default over the same PostgreSQL rows, the report goes from 140 ms to 605 ms
 * and the test fails on the margin — {@code sql=605ms java=432ms}. The ratio is what fires, not the
 * bound: half a second is still inside {@value #BOUND_SECONDS}s at this size, which is precisely why
 * the bound alone would not be a test. On the deployment that prompted this the same fallback did
 * not finish at all.
 */
class Dist17AnalyticsVolumeTest extends AbstractDistTest {

    static final int PROCESSES = 20_000;
    static final int STEPS_PER_PROCESS = 5;
    static final int BOUND_SECONDS = 5;

    static ConfigurableApplicationContext orchestrator;
    static ProcessEntityRepository processEntities;
    static StepExecutionEntityRepository stepEntities;
    static ProcessRepository processes;
    static StepExecutionRepository steps;
    static ProcessAnalyticsService analytics;

    @BeforeAll
    static void startPodAndSeed() {
        orchestrator = DistInfra.startOrchestrator(Map.of());
        processEntities = orchestrator.getBean(ProcessEntityRepository.class);
        stepEntities = orchestrator.getBean(StepExecutionEntityRepository.class);
        processes = orchestrator.getBean(ProcessRepository.class);
        steps = orchestrator.getBean(StepExecutionRepository.class);
        analytics = orchestrator.getBean(ProcessAnalyticsService.class);
        stepEntities.deleteAll();
        processEntities.deleteAll();
        seed();
    }

    @AfterAll
    static void stopPod() {
        if (orchestrator != null) {
            orchestrator.close();
        }
    }

    @Test
    void theReportIsReducedInTheDatabaseAndNotInTheJvm() {
        // Once to let the connection pool and the statement cache warm, so the number that is
        // asserted is the query and not the first-call cost of everything around it.
        analytics.analyzeAll(TimeWindow.all());

        var sqlStart = System.nanoTime();
        var report = analytics.analyzeAll(TimeWindow.all());
        var sqlMillis = (System.nanoTime() - sqlStart) / 1_000_000;

        var javaStart = System.nanoTime();
        var javaSide = rowByRowProcesses().aggregateProcesses(null, null);
        rowByRowSteps().aggregateSteps(null, null);
        var javaMillis = (System.nanoTime() - javaStart) / 1_000_000;

        System.out.printf("VOLUME| %d processes, %d step executions%n",
                PROCESSES, PROCESSES * STEPS_PER_PROCESS);
        System.out.printf("VOLUME| aggregated in SQL:      %5d ms%n", sqlMillis);
        System.out.printf("VOLUME| folded row by row:      %5d ms%n", javaMillis);

        // The report is right, first: a fast wrong answer is not the thing being asserted. The
        // orchestrator imports its own definitions at startup, so the seeded one is found among
        // them rather than being the only one.
        var seeded = report.stream()
                .filter(d -> "load-definition".equals(d.workflowDefinitionId()))
                .findFirst().orElseThrow();
        assertThat(seeded.totalInstances()).isEqualTo(PROCESSES);
        assertThat(seeded.steps()).hasSize(STEPS_PER_PROCESS);
        assertThat(seeded.processDuration().samples()).isEqualTo(PROCESSES);
        assertThat(javaSide.statusCounts()).isNotEmpty();

        assertThat(sqlMillis)
                .as("the whole report over %d processes and %d step executions, in SQL",
                        PROCESSES, PROCESSES * STEPS_PER_PROCESS)
                .isLessThan(BOUND_SECONDS * 1000L);
        assertThat(sqlMillis * 3)
                .as("SQL aggregation must beat folding every row by a margin, not by a nose — "
                        + "sql=%dms java=%dms", sqlMillis, javaMillis)
                .isLessThan(javaMillis);
    }

    /** The row-by-row path: the port's own in-memory default, over the same PostgreSQL rows. */
    private static ProcessRepository rowByRowProcesses() {
        return new ProcessRepository() {
            @Override
            public List<ProcessAnalyticsRow> findAnalyticsRows(LocalDateTime from, LocalDateTime to) {
                return processes.findAnalyticsRows(from, to);
            }

            @Override public Optional<Process> findById(String id) { throw new UnsupportedOperationException(); }
            @Override public String save(Process process) { throw new UnsupportedOperationException(); }
            @Override public List<Process> findAll() { throw new UnsupportedOperationException(); }
            @Override public void deleteAllById(List<String> ids) { throw new UnsupportedOperationException(); }
            @Override public Optional<Process> findByBusinessKey(String key) { throw new UnsupportedOperationException(); }
        };
    }

    private static StepExecutionRepository rowByRowSteps() {
        return new StepExecutionRepository() {
            @Override
            public List<StepExecutionAnalyticsRow> findAnalyticsRows(LocalDateTime from, LocalDateTime to) {
                return steps.findAnalyticsRows(from, to);
            }

            @Override public Optional<StepExecution> findById(String id) { throw new UnsupportedOperationException(); }
            @Override public String save(StepExecution stepExecution) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findAll() { throw new UnsupportedOperationException(); }
            @Override public void deleteAllById(List<String> ids) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findByProcessId(String processId) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findPendingOrRunning() { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findPendingOrRunningByProcessId(String id) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDue(LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDueByProcessId(String id, LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findDueRetriesByProcessId(String id, LocalDateTime now) { throw new UnsupportedOperationException(); }
            @Override public List<StepExecution> findWaitingForMessage(String name, String key) { throw new UnsupportedOperationException(); }
        };
    }

    private static void seed() {
        var base = LocalDateTime.of(2026, 8, 1, 9, 0);
        var processRows = new ArrayList<ProcessEntity>(1000);
        var stepRows = new ArrayList<StepExecutionEntity>(5000);
        for (var i = 0; i < PROCESSES; i++) {
            var id = "p" + i;
            var created = base.plusSeconds(i);
            var finished = created.plusSeconds(1).plusNanos((i % 900) * 1_000_000L);
            processRows.add(new ProcessEntity(id, id, "load", "[]", ProcessStatus.COMPLETED.name(),
                    100, "log", "load-definition", 1, "{}",
                    created, created, finished, null, null, null));
            for (var s = 0; s < STEPS_PER_PROCESS; s++) {
                var startedAt = created.plusNanos(s * 1_000L);
                stepRows.add(step(id + "-s" + s, id, "step-" + s, startedAt,
                        startedAt.plusNanos((10L + s + (i % 90)) * 1_000_000L), s));
            }
            // Flushed in batches: 20 000 processes and 100 000 step executions in one persistence
            // context is a heap problem of this test's own making.
            if (processRows.size() >= 1000) {
                processEntities.saveAll(processRows);
                stepEntities.saveAll(stepRows);
                processRows.clear();
                stepRows.clear();
            }
        }
        processEntities.saveAll(processRows);
        stepEntities.saveAll(stepRows);
    }

    private static StepExecutionEntity step(String id, String processId, String stepId,
                                            LocalDateTime startedAt, LocalDateTime finishedAt, long order) {
        var entity = new StepExecutionEntity();
        entity.setId(id);
        entity.setProcessId(processId);
        entity.setWorkflowDefinitionId("load-definition");
        entity.setStepId(stepId);
        entity.setStatus(StepExecutionStatus.COMPLETED.name());
        entity.setOrder(order);
        entity.setStartedAt(startedAt);
        entity.setFinishedAt(finishedAt);
        entity.setAttemptCount(1);
        return entity;
    }
}
