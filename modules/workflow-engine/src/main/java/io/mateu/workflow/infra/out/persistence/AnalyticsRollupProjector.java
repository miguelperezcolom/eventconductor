package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.application.out.analytics.DurationHistogram;
import io.mateu.workflow.application.out.analytics.RollupFolder;
import io.mateu.workflow.application.out.analytics.RollupModel.CreatedRow;
import io.mateu.workflow.application.out.analytics.RollupModel.FinishedProcessRow;
import io.mateu.workflow.application.out.analytics.RollupModel.FinishedStepRow;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessCreatedDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessDurationDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessFinishedDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.ProcessStatusDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.StepDurationDaily;
import io.mateu.workflow.application.out.analytics.RollupModel.StepStatusDaily;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.AnalyticsProjectionStateEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessCreatedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessFinishedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessStatusDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepStatusDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.AnalyticsProjectionStateRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.AnalyticsSourceRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessCreatedDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessDurationDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessFinishedDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.ProcessStatusDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.StepDurationDailyRepository;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupRepositories.StepStatusDailyRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Keeps the analytics read model current, offline, off the raw tables — folding each immutable fact
 * exactly once and never re-scanning history.
 *
 * <p>Three streams, three cursors: a process the moment it is created, a process the moment it
 * finishes, a step the moment it finishes. Each cursor is a {@code (timestamp, id)} the projector
 * has passed; it reads the next batch strictly after it, folds it into the rollup with
 * {@link RollupFolder}, and advances the cursor — all in one transaction, so a crash re-reads a
 * batch rather than half-applying it, and because the cursor only moves forward a fact is never
 * folded twice. What is still in flight is not folded at all; it is small, and the reader counts it
 * live.
 *
 * <p>The first run finds the cursors at the epoch and drains all of history in batches — that is the
 * backfill, no separate path. A trailing ceiling ({@code now - a couple of seconds}) keeps it off
 * rows still being committed at the boundary. One advisory lock, so several pods do not fold the
 * same batch; held only for the work, which in steady state is a batch or two.
 */
@Service
@ConditionalOnProperty(name = "workflow.analytics.rollup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupProjector {

    // Advisory lock ids in use: 222333444, 444555666, 777888999, and 111222333 here. Keep distinct.
    private static final long LOCK_ID = 111222333L;

    /** The cursor start: before any real timestamp, so the first pass reads everything. */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    final AnalyticsSourceRepository source;
    final ProcessCreatedDailyRepository createdDaily;
    final ProcessFinishedDailyRepository finishedDaily;
    final ProcessStatusDailyRepository statusDaily;
    final ProcessDurationDailyRepository durationDaily;
    final StepStatusDailyRepository stepStatusDaily;
    final StepDurationDailyRepository stepDurationDaily;
    final AnalyticsProjectionStateRepository stateRepository;
    final JdbcTemplate jdbcTemplate;
    final DbLockDialect dbLockDialect;
    final PlatformTransactionManager transactionManager;

    @Value("${workflow.analytics.rollup-interval-ms:15000}")
    long intervalMs;

    @Value("${workflow.analytics.rollup-batch-size:1000}")
    int batchSize;

    /** Kept off rows still landing at the boundary — a produce in flight when the pass began. */
    @Value("${workflow.analytics.rollup-ceiling-seconds:2}")
    long ceilingSeconds;

    /**
     * A ceiling on batches folded per stream per tick, so a first-run backfill of millions does not
     * hold the lock and a connection for one unbounded pass — it advances a bounded slice each tick
     * and catches up over a few of them. Steady state is well under one batch.
     */
    @Value("${workflow.analytics.rollup-max-batches-per-cycle:200}")
    int maxBatchesPerCycle;

    private TransactionTemplate tx;

    @PostConstruct
    public void start() {
        this.tx = new TransactionTemplate(transactionManager);
        var thread = new Thread(() -> {
            try {
                while (true) {
                    try {
                        jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                            if (!dbLockDialect.tryLock(con, LOCK_ID)) {
                                return null;
                            }
                            try {
                                projectOnce();
                            } finally {
                                dbLockDialect.unlock(con, LOCK_ID);
                            }
                            return null;
                        });
                    } catch (Throwable e) {
                        log.error("Error projecting analytics rollup", e);
                    }
                    Thread.sleep(intervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "workflow-analytics-rollup");
        thread.setDaemon(true);
        thread.start();
    }

    void projectOnce() {
        var ceiling = LocalDateTime.now().minusSeconds(ceilingSeconds);
        foldCreated(ceiling);
        foldProcessFinished(ceiling);
        foldStepFinished(ceiling);
    }

    private void foldCreated(LocalDateTime ceiling) {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var state = state();
                var batch = source.processCreationsAfter(
                        state.getCreatedCursorTs(), state.getCreatedCursorId(), ceiling,
                        PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    return false;
                }
                var rows = batch.stream()
                        .filter(v -> v.getCreated() != null)
                        .map(v -> new CreatedRow(v.getWorkflowDefinitionId(), v.getCreated().toLocalDate()))
                        .toList();
                RollupFolder.foldCreated(rows).forEach(this::applyCreated);
                var last = batch.get(batch.size() - 1);
                state.setCreatedCursorTs(last.getCreated());
                state.setCreatedCursorId(last.getId());
                stateRepository.save(state);
                return batch.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    private void foldProcessFinished(LocalDateTime ceiling) {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var state = state();
                var batch = source.processFinishesAfter(
                        state.getPfinishedCursorTs(), state.getPfinishedCursorId(), ceiling,
                        PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    return false;
                }
                var rows = batch.stream()
                        .filter(v -> v.getCreated() != null && v.getFinished() != null)
                        .map(v -> new FinishedProcessRow(v.getWorkflowDefinitionId(),
                                v.getCreated().toLocalDate(), v.getFinished().toLocalDate(),
                                ProcessStatus.valueOf(v.getStatus()), v.getName(),
                                processDurationNanos(v.getStarted(), v.getCreated(), v.getFinished())))
                        .toList();
                var deltas = RollupFolder.foldProcessFinished(rows);
                deltas.finishedPerDay().forEach(this::applyFinished);
                deltas.statusCounts().forEach(this::applyStatus);
                deltas.durations().forEach(this::applyProcessDuration);
                var last = batch.get(batch.size() - 1);
                state.setPfinishedCursorTs(last.getFinished());
                state.setPfinishedCursorId(last.getId());
                stateRepository.save(state);
                return batch.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    private void foldStepFinished(LocalDateTime ceiling) {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var state = state();
                var batch = source.stepFinishesAfter(
                        state.getSfinishedCursorTs(), state.getSfinishedCursorId(), ceiling,
                        PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    return false;
                }
                var rows = batch.stream()
                        .filter(v -> v.getProcessCreated() != null && v.getFinishedAt() != null)
                        .map(v -> new FinishedStepRow(v.getWorkflowDefinitionId(), v.getStepId(),
                                v.getProcessCreated().toLocalDate(),
                                StepExecutionStatus.valueOf(v.getStatus()), v.getStepOrder(),
                                stepDurationNanos(v.getStartedAt(), v.getFinishedAt())))
                        .toList();
                var deltas = RollupFolder.foldStepFinished(rows);
                deltas.statusCounts().forEach(this::applyStepStatus);
                deltas.durations().forEach(this::applyStepDuration);
                var last = batch.get(batch.size() - 1);
                state.setSfinishedCursorTs(last.getFinishedAt());
                state.setSfinishedCursorId(last.getId());
                stateRepository.save(state);
                return batch.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    // ─────────────────────────── upserts: find the row, add the delta, save ───────────────────────────

    private void applyCreated(ProcessCreatedDaily d) {
        var key = key(d.definitionId(), String.valueOf(d.day()));
        var e = createdDaily.findById(key).orElseGet(() ->
                new ProcessCreatedDailyEntity(key, d.definitionId(), d.day(), 0));
        e.setCnt(e.getCnt() + d.count());
        createdDaily.save(e);
    }

    private void applyFinished(ProcessFinishedDaily d) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()), String.valueOf(d.finishedDay()));
        var e = finishedDaily.findById(key).orElseGet(() ->
                new ProcessFinishedDailyEntity(key, d.definitionId(), d.createdDay(), d.finishedDay(), 0));
        e.setCnt(e.getCnt() + d.count());
        finishedDaily.save(e);
    }

    private void applyStatus(ProcessStatusDaily d) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()), d.status().name());
        var e = statusDaily.findById(key).orElseGet(() ->
                new ProcessStatusDailyEntity(key, d.definitionId(), d.createdDay(), d.status().name(), 0,
                        d.anyName()));
        e.setCnt(e.getCnt() + d.count());
        if (e.getAnyName() == null && d.anyName() != null) {
            e.setAnyName(d.anyName());
        }
        statusDaily.save(e);
    }

    private void applyProcessDuration(ProcessDurationDaily d) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()));
        var e = durationDaily.findById(key).orElse(null);
        if (e == null) {
            durationDaily.save(new ProcessDurationDailyEntity(key, d.definitionId(), d.createdDay(),
                    d.samples(), d.totalNanos(), d.histogram().serialize()));
            return;
        }
        var merged = DurationHistogram.parse(e.getHistogram());
        merged.mergeIn(d.histogram());
        e.setSamples(e.getSamples() + d.samples());
        e.setTotalNanos(e.getTotalNanos() + d.totalNanos());
        e.setHistogram(merged.serialize());
        durationDaily.save(e);
    }

    private void applyStepStatus(StepStatusDaily d) {
        var key = key(d.definitionId(), d.stepId(), String.valueOf(d.createdDay()), d.status().name());
        var e = stepStatusDaily.findById(key).orElseGet(() ->
                new StepStatusDailyEntity(key, d.definitionId(), d.stepId(), d.createdDay(),
                        d.status().name(), 0, d.firstOrder()));
        e.setCnt(e.getCnt() + d.count());
        e.setFirstOrder(Math.min(e.getFirstOrder(), d.firstOrder()));
        stepStatusDaily.save(e);
    }

    private void applyStepDuration(StepDurationDaily d) {
        var key = key(d.definitionId(), d.stepId(), String.valueOf(d.createdDay()));
        var e = stepDurationDaily.findById(key).orElse(null);
        if (e == null) {
            stepDurationDaily.save(new StepDurationDailyEntity(key, d.definitionId(), d.stepId(),
                    d.createdDay(), d.samples(), d.totalNanos(), d.histogram().serialize()));
            return;
        }
        var merged = DurationHistogram.parse(e.getHistogram());
        merged.mergeIn(d.histogram());
        e.setSamples(e.getSamples() + d.samples());
        e.setTotalNanos(e.getTotalNanos() + d.totalNanos());
        e.setHistogram(merged.serialize());
        stepDurationDaily.save(e);
    }

    private AnalyticsProjectionStateEntity state() {
        return stateRepository.findById(1).orElseGet(() -> stateRepository.save(
                new AnalyticsProjectionStateEntity(1, EPOCH, "", EPOCH, "", EPOCH, "")));
    }

    private static Long processDurationNanos(LocalDateTime started, LocalDateTime created,
                                             LocalDateTime finished) {
        var start = started != null ? started : created;
        if (start == null || finished == null) {
            return null;
        }
        return java.time.Duration.between(start, finished).toNanos();
    }

    private static Long stepDurationNanos(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return java.time.Duration.between(startedAt, finishedAt).toNanos();
    }

    private static String key(String... parts) {
        return String.join(" ", parts);
    }
}
