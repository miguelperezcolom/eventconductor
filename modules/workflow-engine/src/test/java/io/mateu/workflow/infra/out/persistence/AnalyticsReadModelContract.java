package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionDayCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionDuration;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStatusCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStepCount;
import io.mateu.workflow.application.out.AnalyticsAggregates.DefinitionStepDuration;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The analytics read model end to end over a real database: {@link AnalyticsRollupProjector} folds
 * raw {@code process_entity}/{@code step_execution_entity} rows into the daily rollups, and
 * {@link RollupAnalyticsReader} answers the report from them plus the live overlay. The pure
 * folding and reducing are covered by {@code RollupEquivalenceTest}; what this pins is the glue the
 * pure tests cannot see — the cursored JPQL, the upserts, the cursor state and its transactions,
 * the window queries — on the schema the migrations create.
 *
 * <p>Written once, run against H2 (always) and PostgreSQL (when Docker is there), because the
 * nullable-window {@code cast(:from as …) is null} idiom and the timestamp comparisons are exactly
 * the kind of SQL that behaves differently on the database production runs.
 */
abstract class AnalyticsReadModelContract {

    static final String ORDER = "order";
    static final String REFUND = "refund";
    static final LocalDate D1 = LocalDate.of(2026, 1, 10);
    static final LocalDate D2 = D1.plusDays(1);

    DataSource dataSource;
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;

    /** A new database with the engine's migrations applied, for one test. */
    abstract DataSource newMigratedDatabase();

    /** The lock dialect the projector's loop would get on this database. */
    abstract DbLockDialect dialect();

    /** Releases what {@link #newMigratedDatabase()} opened. */
    abstract void dispose(DataSource dataSource);

    @BeforeEach
    void openContext() {
        dataSource = newMigratedDatabase();
        context = AnalyticsJpaTestContext.open(dataSource);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
        if (dataSource != null) {
            dispose(dataSource);
        }
    }

    // ─────────────────────────── wiring ───────────────────────────

    AnalyticsRollupProjector projector(DbLockDialect dialect) {
        var projector = new AnalyticsRollupProjector(
                context.getBean(AnalyticsSourceRepository.class),
                context.getBean(ProcessCreatedDailyRepository.class),
                context.getBean(ProcessFinishedDailyRepository.class),
                context.getBean(ProcessStatusDailyRepository.class),
                context.getBean(ProcessDurationDailyRepository.class),
                context.getBean(StepStatusDailyRepository.class),
                context.getBean(StepDurationDailyRepository.class),
                context.getBean(AnalyticsProjectionStateRepository.class),
                jdbc,
                dialect,
                context.getBean(PlatformTransactionManager.class));
        projector.intervalMs = 15_000;
        projector.batchSize = 1000;
        projector.ceilingSeconds = 2;
        projector.maxBatchesPerCycle = 200;
        return projector;
    }

    /** A projector whose fold is driven by the test ({@link AnalyticsRollupProjector#projectOnce}), no thread. */
    AnalyticsRollupProjector projector() {
        var projector = projector(dialect());
        ReflectionTestUtils.setField(projector, "tx",
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)));
        return projector;
    }

    RollupAnalyticsReader reader() {
        return new RollupAnalyticsReader(
                context.getBean(ProcessCreatedDailyRepository.class),
                context.getBean(ProcessFinishedDailyRepository.class),
                context.getBean(ProcessStatusDailyRepository.class),
                context.getBean(ProcessDurationDailyRepository.class),
                context.getBean(StepStatusDailyRepository.class),
                context.getBean(StepDurationDailyRepository.class),
                context.getBean(LiveOverlayRepository.class));
    }

    // ─────────────────────────── fixture ───────────────────────────

    static LocalDateTime at(LocalDate day, int hour, int minute, int second) {
        return day.atTime(hour, minute, second);
    }

    void process(String id, String definition, ProcessStatus status, String name,
                 LocalDateTime created, LocalDateTime started, LocalDateTime finished) {
        jdbc.update("INSERT INTO process_entity (id, business_key, name, status, workflow_definition_id, "
                        + "created, started, finished) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, "bk-" + id, name, status.name(), definition, ts(created), ts(started), ts(finished));
    }

    void step(String id, String processId, String definition, String stepId, StepExecutionStatus status,
              long order, LocalDateTime startedAt, LocalDateTime finishedAt) {
        jdbc.update("INSERT INTO step_execution_entity (id, process_id, workflow_definition_id, step_id, "
                        + "status, _order, started_at, finished_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, processId, definition, stepId, status.name(), order, ts(startedAt), ts(finishedAt));
    }

    void finishProcess(String id, ProcessStatus status, LocalDateTime finished) {
        jdbc.update("UPDATE process_entity SET status = ?, finished = ? WHERE id = ?",
                status.name(), ts(finished), id);
    }

    void finishStep(String id, StepExecutionStatus status, LocalDateTime finishedAt) {
        jdbc.update("UPDATE step_execution_entity SET status = ?, finished_at = ? WHERE id = ?",
                status.name(), ts(finishedAt), id);
    }

    private static Timestamp ts(LocalDateTime moment) {
        return moment == null ? null : Timestamp.valueOf(moment);
    }

    /**
     * Two definitions over two days: a completed, a failed and a still-running {@code order}, a
     * cancelled {@code refund}; three finished steps and one still running.
     */
    void seed() {
        process("p1", ORDER, ProcessStatus.COMPLETED, "order-1",
                at(D1, 9, 0, 0), at(D1, 9, 0, 0), at(D1, 9, 0, 2));
        // No start time: the duration falls back to the creation time (22 hours, finished next day).
        process("p2", ORDER, ProcessStatus.ERROR, "order-2",
                at(D1, 10, 0, 0), null, at(D2, 8, 0, 0));
        process("p3", ORDER, ProcessStatus.RUNNING, "order-3",
                at(D2, 12, 0, 0), at(D2, 12, 0, 0), null);
        process("p4", REFUND, ProcessStatus.CANCELLED, "refund-1",
                at(D2, 13, 0, 0), at(D2, 13, 0, 0), at(D2, 13, 0, 5));

        step("s1", "p1", ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, at(D1, 9, 0, 0), at(D1, 9, 0, 1));
        step("s2", "p1", ORDER, "charge", StepExecutionStatus.COMPLETED, 2, at(D1, 9, 0, 1), at(D1, 9, 0, 2));
        step("s3", "p2", ORDER, "reserve", StepExecutionStatus.ERROR, 1, at(D1, 10, 0, 0), at(D1, 10, 0, 3));
        step("s4", "p3", ORDER, "reserve", StepExecutionStatus.RUNNING, 1, at(D2, 12, 0, 0), null);
    }

    /** Every rollup row and the cursor state, as text — to compare whole read models. */
    Map<String, List<Map<String, Object>>> snapshot() {
        return Map.of(
                "created", jdbc.queryForList("SELECT * FROM process_created_daily ORDER BY k"),
                "finished", jdbc.queryForList("SELECT * FROM process_finished_daily ORDER BY k"),
                "status", jdbc.queryForList("SELECT * FROM process_status_daily ORDER BY k"),
                "duration", jdbc.queryForList("SELECT * FROM process_duration_daily ORDER BY k"),
                "stepStatus", jdbc.queryForList("SELECT * FROM step_status_daily ORDER BY k"),
                "stepDuration", jdbc.queryForList("SELECT * FROM step_duration_daily ORDER BY k"),
                "state", jdbc.queryForList("SELECT * FROM analytics_projection_state"));
    }

    long createdCount() {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(cnt), 0) FROM process_created_daily", Long.class);
        return sum == null ? 0 : sum;
    }

    static long nanos(Duration duration) {
        return duration.toNanos();
    }

    // ─────────────────────────── tests ───────────────────────────

    @Test
    void anEmptyDatabaseFoldsToAnEmptyReport() {
        projector().projectOnce();

        var processes = reader().aggregateProcesses(null, null);
        assertThat(processes.statusCounts()).isEmpty();
        assertThat(processes.createdPerDay()).isEmpty();
        assertThat(processes.finishedPerDay()).isEmpty();
        assertThat(processes.durations()).isEmpty();
        var steps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).isEmpty();
        assertThat(steps.durations()).isEmpty();
    }

    @Test
    void theFirstPassBackfillsHistoryAndTheReaderAddsWhatIsInFlight() {
        seed();

        projector().projectOnce();
        var report = reader().aggregateProcesses(null, null);

        // Terminal statuses come from the rollup; the running p3 from the live overlay on top.
        assertThat(report.statusCounts()).containsExactlyInAnyOrder(
                new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 1, "order-1"),
                new DefinitionStatusCount(ORDER, ProcessStatus.ERROR, 1, "order-2"),
                new DefinitionStatusCount(REFUND, ProcessStatus.CANCELLED, 1, "refund-1"),
                new DefinitionStatusCount(ORDER, ProcessStatus.RUNNING, 1, "order-3"));
        // Created per day counts every process, finished or not.
        assertThat(report.createdPerDay()).containsExactlyInAnyOrder(
                new DefinitionDayCount(ORDER, D1, 2),
                new DefinitionDayCount(ORDER, D2, 1),
                new DefinitionDayCount(REFUND, D2, 1));
        // Finished per day is bucketed by the day it finished: p2 was created on D1, finished D2.
        assertThat(report.finishedPerDay()).containsExactlyInAnyOrder(
                new DefinitionDayCount(ORDER, D1, 1),
                new DefinitionDayCount(ORDER, D2, 1),
                new DefinitionDayCount(REFUND, D2, 1));

        var durations = report.durations().stream()
                .collect(java.util.stream.Collectors.toMap(DefinitionDuration::workflowDefinitionId,
                        DefinitionDuration::duration));
        assertThat(durations).containsOnlyKeys(ORDER, REFUND);
        assertThat(durations.get(ORDER).samples()).isEqualTo(2);
        assertThat(durations.get(ORDER).totalNanos())
                .isEqualTo(nanos(Duration.ofSeconds(2)) + nanos(Duration.ofHours(22)));
        assertThat(durations.get(REFUND).samples()).isEqualTo(1);
        assertThat(durations.get(REFUND).totalNanos()).isEqualTo(nanos(Duration.ofSeconds(5)));
        // p95 is read off a histogram: never under the exact value, over it by at most a bucket.
        assertThat(durations.get(REFUND).p95Nanos())
                .isBetween(nanos(Duration.ofSeconds(5)), nanos(Duration.ofMillis(6_250)));

        var steps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, 1),
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.ERROR, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.COMPLETED, 1, 2),
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.RUNNING, 1, 1));
        var stepDurations = steps.durations().stream()
                .collect(java.util.stream.Collectors.toMap(DefinitionStepDuration::stepId,
                        DefinitionStepDuration::duration));
        assertThat(stepDurations).containsOnlyKeys("reserve", "charge");
        assertThat(stepDurations.get("reserve").samples()).isEqualTo(2);
        assertThat(stepDurations.get("reserve").totalNanos()).isEqualTo(nanos(Duration.ofSeconds(4)));
        assertThat(stepDurations.get("charge").samples()).isEqualTo(1);
        assertThat(stepDurations.get("charge").totalNanos()).isEqualTo(nanos(Duration.ofSeconds(1)));
    }

    @Test
    void foldingAgainChangesNothing() {
        seed();
        var projector = projector();
        projector.projectOnce();
        var once = snapshot();

        // The same pod again, and another pod sharing the database (its own projector, same cursor).
        projector.projectOnce();
        projector().projectOnce();

        assertThat(snapshot()).isEqualTo(once);
        assertThat(createdCount()).isEqualTo(4);
    }

    @Test
    void aProcessThatFinishesLaterIsFoldedOnceAndLeavesTheLiveOverlay() {
        seed();
        var projector = projector();
        projector.projectOnce();

        // Later than every finish already folded, as a real finish is (the cursor is on finish time).
        finishProcess("p3", ProcessStatus.COMPLETED, at(D2, 14, 0, 0));
        finishStep("s4", StepExecutionStatus.COMPLETED, at(D2, 12, 0, 4));
        projector.projectOnce();

        var report = reader().aggregateProcesses(null, null);
        assertThat(report.statusCounts()).containsExactlyInAnyOrder(
                new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 2, "order-1"),
                new DefinitionStatusCount(ORDER, ProcessStatus.ERROR, 1, "order-2"),
                new DefinitionStatusCount(REFUND, ProcessStatus.CANCELLED, 1, "refund-1"));
        // Its creation was folded on the first pass and is not folded again by its finish.
        assertThat(report.createdPerDay()).contains(new DefinitionDayCount(ORDER, D2, 1));
        assertThat(createdCount()).isEqualTo(4);

        var steps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 2, 1),
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.ERROR, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.COMPLETED, 1, 2));
    }

    @Test
    void aWindowSelectsByCreationDay() {
        seed();
        projector().projectOnce();

        var from = D2.atStartOfDay();
        var to = D2.atTime(23, 59, 59);
        var report = reader().aggregateProcesses(from, to);

        // p1 and p2 were created on D1: out, even though p2 finished on D2.
        assertThat(report.statusCounts()).containsExactlyInAnyOrder(
                new DefinitionStatusCount(REFUND, ProcessStatus.CANCELLED, 1, "refund-1"),
                new DefinitionStatusCount(ORDER, ProcessStatus.RUNNING, 1, "order-3"));
        assertThat(report.createdPerDay()).containsExactlyInAnyOrder(
                new DefinitionDayCount(ORDER, D2, 1),
                new DefinitionDayCount(REFUND, D2, 1));
        assertThat(report.finishedPerDay()).containsExactly(new DefinitionDayCount(REFUND, D2, 1));

        var steps = reader().aggregateSteps(from, to);
        assertThat(steps.counts()).containsExactly(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.RUNNING, 1, 1));
        assertThat(steps.durations()).isEmpty();

        // Open on one side only.
        assertThat(reader().aggregateProcesses(null, D1.atTime(23, 0)).createdPerDay())
                .containsExactly(new DefinitionDayCount(ORDER, D1, 2));
        assertThat(reader().aggregateProcesses(D2.plusDays(1).atStartOfDay(), null).statusCounts()).isEmpty();
    }

    @Test
    void batchesAreBoundedAndTheCursorBreaksTimestampTiesById() {
        // Three processes created in the same instant: only the (timestamp, id) cursor tells them apart.
        var instant = at(D1, 8, 0, 0);
        process("a", ORDER, ProcessStatus.COMPLETED, "a", instant, instant, instant.plusSeconds(1));
        process("b", ORDER, ProcessStatus.COMPLETED, "b", instant, instant, instant.plusSeconds(1));
        process("c", ORDER, ProcessStatus.COMPLETED, "c", instant, instant, instant.plusSeconds(1));
        var projector = projector();
        projector.batchSize = 1;
        projector.maxBatchesPerCycle = 2;

        projector.projectOnce();
        assertThat(createdCount()).as("two batches of one per pass").isEqualTo(2);

        projector.projectOnce();
        assertThat(createdCount()).as("the next pass resumes after b, not at the start").isEqualTo(3);

        projector.projectOnce();
        assertThat(createdCount()).isEqualTo(3);
        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 3, "a"));
    }

    @Test
    void rowsInsideTheTrailingCeilingWaitForTheNextPass() {
        var now = LocalDateTime.now();
        process("fresh", ORDER, ProcessStatus.COMPLETED, "fresh", now, now, now);
        var projector = projector();
        projector.ceilingSeconds = 3600;

        projector.projectOnce();
        assertThat(createdCount()).isZero();
        assertThat(reader().aggregateProcesses(null, null).statusCounts()).isEmpty();

        projector.ceilingSeconds = -60; // the ceiling now lies ahead of the row
        projector.projectOnce();
        assertThat(createdCount()).isEqualTo(1);
        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 1, "fresh"));
    }

    @Test
    void theBackgroundLoopFoldsUnderItsLockUntilStopped() throws Exception {
        seed();
        var projector = projector(dialect());
        projector.intervalMs = 50;
        try {
            projector.start();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(createdCount()).isEqualTo(4));
        } finally {
            projector.stop();
        }
        var thread = (Thread) ReflectionTestUtils.getField(projector, "thread");
        thread.join(5_000);
        assertThat(thread.isAlive()).as("the loop ends with its context").isFalse();
    }

    /**
     * Known defect, reported rather than fixed here: a finished process is treated as an immutable
     * fact, but {@code RetryProcessUseCase} and {@code RestartProcessUseCase} reopen it
     * ({@code finished = null}). The fold of its first finish is never retracted, so the live
     * overlay counts it again while it runs, and its second finish is folded on top: one process,
     * counted twice. Enable this test with the fix.
     */
    @Test
    @Disabled("Known defect: a retried/restarted process is double-counted by the read model")
    void aRetriedProcessIsCountedOnce() {
        process("r", ORDER, ProcessStatus.ERROR, "r", at(D1, 8, 0, 0), at(D1, 8, 0, 0), at(D1, 8, 0, 1));
        var projector = projector();
        projector.projectOnce();

        // Operator retries it: back to RUNNING, finished cleared (RetryProcessUseCase).
        jdbc.update("UPDATE process_entity SET status = 'RUNNING', finished = NULL WHERE id = 'r'");
        projector.projectOnce();

        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.RUNNING, 1, "r"));
    }
}
