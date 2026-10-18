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
                context.getBean(CountedProcessRepository.class),
                context.getBean(CountedStepRepository.class),
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

    // ─────────────────────────── a counted fact taken back out: retry, restart, step reset ───────────────────────────

    /** Clears a process's finish and puts it back to RUNNING, as Retry/RestartProcessUseCase do. */
    void reopenProcess(String id) {
        jdbc.update("UPDATE process_entity SET status = 'RUNNING', finished = NULL WHERE id = ?", id);
    }

    /** Resets a step execution to CREATED, as a retry ({@code updateStatus(CREATED)}) does. */
    void resetStep(String id) {
        jdbc.update("UPDATE step_execution_entity SET status = 'CREATED', finished_at = NULL WHERE id = ?", id);
    }

    /** ...and as a restart ({@code resetForRerun}) does: its start goes too. */
    void resetStepForRerun(String id) {
        jdbc.update("UPDATE step_execution_entity SET status = 'CREATED', started_at = NULL, "
                + "finished_at = NULL WHERE id = ?", id);
    }

    void startStep(String id, LocalDateTime startedAt) {
        jdbc.update("UPDATE step_execution_entity SET status = 'RUNNING', started_at = ? WHERE id = ?",
                ts(startedAt), id);
    }

    long ledgerRows(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    /**
     * The report after everything that happened, against the report a projector folding today's raw
     * rows from scratch produces. Folding incrementally through reopens and second finishes must
     * land exactly where a fresh backfill does — the definition of "counted once".
     */
    void assertEqualsAFreshRebuild() {
        var processes = reader().aggregateProcesses(null, null);
        var steps = reader().aggregateSteps(null, null);
        var rollups = snapshot();

        for (var table : List.of("process_created_daily", "process_finished_daily", "process_status_daily",
                "process_duration_daily", "step_status_daily", "step_duration_daily",
                "analytics_counted_process", "analytics_counted_step", "analytics_projection_state")) {
            jdbc.update("DELETE FROM " + table);
        }
        projector().projectOnce();

        var rebuilt = reader().aggregateProcesses(null, null);
        assertThat(processes.statusCounts()).containsExactlyInAnyOrderElementsOf(rebuilt.statusCounts());
        assertThat(processes.createdPerDay()).containsExactlyInAnyOrderElementsOf(rebuilt.createdPerDay());
        assertThat(processes.finishedPerDay()).containsExactlyInAnyOrderElementsOf(rebuilt.finishedPerDay());
        assertThat(processes.durations()).containsExactlyInAnyOrderElementsOf(rebuilt.durations());
        var rebuiltSteps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).containsExactlyInAnyOrderElementsOf(rebuiltSteps.counts());
        assertThat(steps.durations()).containsExactlyInAnyOrderElementsOf(rebuiltSteps.durations());
        // Row for row, too: no zero rows left behind by a retraction.
        var fresh = snapshot();
        for (var table : List.of("created", "finished", "status", "duration", "stepStatus", "stepDuration")) {
            assertThat(rollups.get(table)).as(table).isEqualTo(fresh.get(table));
        }
    }

    @Test
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

    @Test
    void aRetriedProcessThatFinishesAgainIsCountedOnceWithItsNewOutcome() {
        process("r", ORDER, ProcessStatus.ERROR, "r", at(D1, 8, 0, 0), at(D1, 8, 0, 0), at(D1, 8, 0, 3));
        step("r1", "r", ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, at(D1, 8, 0, 0), at(D1, 8, 0, 1));
        step("r2", "r", ORDER, "charge", StepExecutionStatus.ERROR, 2, at(D1, 8, 0, 1), at(D1, 8, 0, 3));
        var projector = projector();
        projector.projectOnce();

        // RetryProcessUseCase: the failed step back to CREATED, the process back to RUNNING.
        resetStep("r2");
        reopenProcess("r");
        projector.projectOnce();

        var reopened = reader().aggregateProcesses(null, null);
        assertThat(reopened.statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.RUNNING, 1, "r"));
        assertThat(reopened.finishedPerDay()).isEmpty();
        assertThat(reopened.durations()).isEmpty();
        assertThat(reopened.createdPerDay()).containsExactly(new DefinitionDayCount(ORDER, D1, 1));
        var reopenedSteps = reader().aggregateSteps(null, null);
        assertThat(reopenedSteps.counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.CREATED, 1, 2));
        assertThat(reopenedSteps.durations()).extracting(DefinitionStepDuration::stepId)
                .containsExactly("reserve");
        assertThat(ledgerRows("analytics_counted_process")).isZero();
        assertThat(ledgerRows("analytics_counted_step")).isEqualTo(1);

        // The retry succeeds, on the next day.
        startStep("r2", at(D2, 9, 0, 0));
        finishStep("r2", StepExecutionStatus.COMPLETED, at(D2, 9, 0, 2));
        finishProcess("r", ProcessStatus.COMPLETED, at(D2, 9, 0, 2));
        projector.projectOnce();

        var report = reader().aggregateProcesses(null, null);
        assertThat(report.statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 1, "r"));
        assertThat(report.finishedPerDay()).containsExactly(new DefinitionDayCount(ORDER, D2, 1));
        assertThat(report.durations()).singleElement()
                .satisfies(d -> assertThat(d.duration().samples()).isEqualTo(1));
        assertThat(reader().aggregateSteps(null, null).counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.COMPLETED, 1, 2));
        assertEqualsAFreshRebuild();
    }

    @Test
    void aRestartedProcessIsCountedOnce() {
        process("x", ORDER, ProcessStatus.CANCELLED, "x", at(D1, 8, 0, 0), at(D1, 8, 0, 0), at(D1, 8, 0, 5));
        step("x1", "x", ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, at(D1, 8, 0, 0), at(D1, 8, 0, 1));
        step("x2", "x", ORDER, "charge", StepExecutionStatus.CANCELLED, 2, at(D1, 8, 0, 1), at(D1, 8, 0, 5));
        var projector = projector();
        projector.projectOnce();

        // RestartProcessUseCase: every step reset for a rerun, including the one that succeeded.
        resetStepForRerun("x1");
        resetStepForRerun("x2");
        reopenProcess("x");
        projector.projectOnce();

        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.RUNNING, 1, "x"));
        var steps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.CREATED, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.CREATED, 1, 2));
        assertThat(steps.durations()).isEmpty();
        assertThat(ledgerRows("analytics_counted_step")).isZero();

        // The rerun fails this time.
        startStep("x1", at(D1, 10, 0, 0));
        finishStep("x1", StepExecutionStatus.COMPLETED, at(D1, 10, 0, 1));
        startStep("x2", at(D1, 10, 0, 1));
        finishStep("x2", StepExecutionStatus.ERROR, at(D1, 10, 0, 4));
        finishProcess("x", ProcessStatus.ERROR, at(D1, 10, 0, 4));
        projector.projectOnce();

        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.ERROR, 1, "x"));
        assertThat(reader().aggregateSteps(null, null).counts()).containsExactlyInAnyOrder(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 1, 1),
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.ERROR, 1, 2));
        assertEqualsAFreshRebuild();
    }

    @Test
    void aStepExecutionResetToCreatedIsCountedOnce() {
        // The process never finished; one of its steps failed and the engine sends it round again.
        process("s", ORDER, ProcessStatus.RUNNING, "s", at(D1, 8, 0, 0), at(D1, 8, 0, 0), null);
        step("s1", "s", ORDER, "charge", StepExecutionStatus.ERROR, 1, at(D1, 8, 0, 0), at(D1, 8, 0, 2));
        var projector = projector();
        projector.projectOnce();
        assertThat(reader().aggregateSteps(null, null).counts()).containsExactly(
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.ERROR, 1, 1));

        resetStep("s1");
        projector.projectOnce();
        var steps = reader().aggregateSteps(null, null);
        assertThat(steps.counts()).containsExactly(
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.CREATED, 1, 1));
        assertThat(steps.durations()).isEmpty();

        startStep("s1", at(D1, 9, 0, 0));
        finishStep("s1", StepExecutionStatus.COMPLETED, at(D1, 9, 0, 1));
        projector.projectOnce();
        assertThat(reader().aggregateSteps(null, null).counts()).containsExactly(
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.COMPLETED, 1, 1));
        assertEqualsAFreshRebuild();
    }

    @Test
    void reopenedAndFinishedAgainBetweenTwoPassesSwapsTheContribution() {
        process("q", ORDER, ProcessStatus.ERROR, "q", at(D1, 8, 0, 0), at(D1, 8, 0, 0), at(D1, 8, 0, 1));
        step("q1", "q", ORDER, "charge", StepExecutionStatus.ERROR, 1, at(D1, 8, 0, 0), at(D1, 8, 0, 1));
        var projector = projector();
        projector.projectOnce();

        // Retried and done again before the projector looks: it never sees the row reopened.
        startStep("q1", at(D1, 9, 0, 0));
        finishStep("q1", StepExecutionStatus.COMPLETED, at(D1, 9, 0, 3));
        finishProcess("q", ProcessStatus.COMPLETED, at(D1, 9, 0, 3));
        projector.projectOnce();

        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .containsExactly(new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 1, "q"));
        assertThat(reader().aggregateSteps(null, null).counts()).containsExactly(
                new DefinitionStepCount(ORDER, "charge", StepExecutionStatus.COMPLETED, 1, 1));
        assertEqualsAFreshRebuild();
    }

    @Test
    void retractingIsIdempotentAcrossPassesAndPods() {
        seed();
        var projector = projector();
        projector.projectOnce();

        resetStep("s3");
        reopenProcess("p2");
        projector.projectOnce();
        var once = snapshot();

        // The same pod again, and another pod with its own projector over the same database.
        projector.projectOnce();
        projector().projectOnce();
        assertThat(snapshot()).isEqualTo(once);
        assertEqualsAFreshRebuild();
    }

    @Test
    void aReplayedFinishBatchIsANoOp() {
        seed();
        var projector = projector();
        projector.projectOnce();
        var once = snapshot();

        // Rewind the finish cursors to the epoch: every finish is read again. The ledger turns each
        // re-read into "take out what was added, add it again" — nothing changes but the cursors.
        jdbc.update("UPDATE analytics_projection_state SET pfinished_cursor_ts = ?, pfinished_cursor_id = '', "
                + "sfinished_cursor_ts = ?, sfinished_cursor_id = ''",
                ts(LocalDateTime.of(1970, 1, 1, 0, 0)), ts(LocalDateTime.of(1970, 1, 1, 0, 0)));
        projector.projectOnce();

        assertThat(snapshot()).isEqualTo(once);
    }

    @Test
    void aFinishCommittedBehindTheCursorIsFoldedOnceByTheLookback() {
        seed();
        var projector = projector();
        projector.lookbackSeconds = 120;
        projector.projectOnce();
        var once = snapshot();
        projector.projectOnce();
        // Nothing behind the cursor that is not counted: the second look changes nothing.
        assertThat(snapshot()).isEqualTo(once);

        // The finish cursors stand at p4's finish (D2 13:00:05) and s3's (D1 10:00:03). These commit
        // only now, stamped before them — a long transaction, a pod whose clock runs behind.
        finishProcess("p3", ProcessStatus.COMPLETED, at(D2, 13, 0, 1));
        finishStep("s4", StepExecutionStatus.COMPLETED, at(D1, 10, 0, 2));
        projector.projectOnce();

        assertThat(reader().aggregateProcesses(null, null).statusCounts()).containsExactlyInAnyOrder(
                new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 2, "order-1"),
                new DefinitionStatusCount(ORDER, ProcessStatus.ERROR, 1, "order-2"),
                new DefinitionStatusCount(REFUND, ProcessStatus.CANCELLED, 1, "refund-1"));
        assertThat(reader().aggregateSteps(null, null).counts()).contains(
                new DefinitionStepCount(ORDER, "reserve", StepExecutionStatus.COMPLETED, 2, 1));

        // And once only.
        var twice = snapshot();
        projector.projectOnce();
        projector().projectOnce();
        assertThat(snapshot()).isEqualTo(twice);
    }

    @Test
    void withoutTheLookbackAFinishBehindTheCursorWasNeverFolded() {
        seed();
        var projector = projector();
        projector.projectOnce();
        finishProcess("p3", ProcessStatus.COMPLETED, at(D2, 13, 0, 1));
        projector.projectOnce();
        // What the lookback exists for: the cursor alone never sees it.
        assertThat(reader().aggregateProcesses(null, null).statusCounts())
                .doesNotContain(new DefinitionStatusCount(ORDER, ProcessStatus.COMPLETED, 2, "order-1"));
    }
}
