package io.mateu.workflow.infra.out.persistence;

import io.mateu.workflow.time.EngineClock;
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
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.CountedProcessEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.CountedStepEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessCreatedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessFinishedDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.ProcessStatusDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepDurationDailyEntity;
import io.mateu.workflow.infra.out.persistence.AnalyticsRollupEntities.StepStatusDailyEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps the analytics read model current, offline, off the raw tables — counting each fact at most
 * once and never re-scanning history.
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
 *
 * <p><b>A finish is not immutable.</b> Retrying or restarting a process clears its {@code finished}
 * and resets its step executions; the engine's own step retry clears a step's {@code finished_at}.
 * So every contribution the projector adds is also written to a ledger
 * ({@code analytics_counted_process} / {@code analytics_counted_step}, keyed by the row's id) in the
 * same transaction. Each pass first retracts the contribution of every counted row that has since
 * reopened (it is in flight again, and the live overlay counts it), and a finish whose row is already
 * in the ledger — reopened and finished again between two passes, or a replayed batch — has its old
 * contribution subtracted before the new one is added. A row is therefore counted at most once,
 * whatever happens to it, and re-folding the same fact is a no-op rather than something only the
 * cursor prevents. A rollup row a retraction brings to zero is deleted, so the read model after any
 * sequence of reopen/finish equals a fresh rebuild from the raw tables.
 */
@Service
@ConditionalOnProperty(name = "workflow.analytics.rollup", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsRollupProjector implements io.mateu.workflow.infra.in.scheduler.SchedulerPass {

    // Advisory lock ids in use: 111222333 (the outbox relay gate, held shared by every relay while it
    // drains), 222333444 (cron), 444555666 (embedded relay), 777888999 (timeouts), 888999111 (lock
    // lease reaper), and 333444555 here. Keep distinct: this lock used to be 111222333, so while any
    // relay drained the projector could not fold, and while the projector folded no relay published.
    static final long LOCK_ID = 333444555L;

    /** The cursor start: before any real timestamp, so the first pass reads everything. */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    final AnalyticsSourceRepository source;
    final ProcessCreatedDailyRepository createdDaily;
    final ProcessFinishedDailyRepository finishedDaily;
    final ProcessStatusDailyRepository statusDaily;
    final ProcessDurationDailyRepository durationDaily;
    final StepStatusDailyRepository stepStatusDaily;
    final StepDurationDailyRepository stepDurationDaily;
    final CountedProcessRepository countedProcesses;
    final CountedStepRepository countedSteps;
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

    /**
     * How far behind the finish cursors each pass looks again for finishes that were never counted.
     * The cursor trusts that a row's timestamp is no older than its commit, give or take the ceiling;
     * a transaction that ran longer, or a pod whose clock is behind, commits a finish the cursor has
     * already passed, and without this it was never folded. The ledger makes the second look safe —
     * only what it does not hold is folded — so this is a cost knob, not a correctness one: it must
     * outlast the longest transaction that finishes a process plus a pass interval. 0 turns it off.
     */
    @Value("${workflow.analytics.rollup-lookback-seconds:120}")
    long lookbackSeconds;

    @Value("${" + BACKGROUND_PROPERTY + ":true}")
    boolean background = true;

    private TransactionTemplate tx;
    private volatile boolean running = true;
    private Thread thread;

    @PostConstruct
    public void start() {
        this.tx = new TransactionTemplate(transactionManager);
        if (!background) {
            return;
        }
        var thread = new Thread(() -> {
            try {
                while (running) {
                    try {
                        runOnce();
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
        this.thread = thread;
        thread.start();
    }

    /**
     * Ends the loop with its context. Without it the thread outlived a closed context and kept
     * borrowing from a closed pool, logging a failure every interval for the rest of the JVM's life.
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** One projection pass under the cluster-wide advisory lock (always reports zero: it folds, it does not count). */
    @Override
    public int runOnce() {
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
        return 0;
    }

    void projectOnce() {
        var ceiling = EngineClock.now().minusSeconds(ceilingSeconds);
        retractReopenedProcesses();
        retractReopenedSteps();
        foldCreated(ceiling);
        foldProcessFinished(ceiling);
        foldStepFinished(ceiling);
        if (lookbackSeconds > 0) {
            foldProcessFinishesBehindTheCursor();
            foldStepFinishesBehindTheCursor();
        }
    }

    /** Folds the finishes the process cursor skipped: committed after it had passed their timestamp. */
    private void foldProcessFinishesBehindTheCursor() {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var state = state();
                // Page 0 every time: what a batch folds enters the ledger and drops out of the query.
                var batch = source.uncountedProcessFinishesBehind(
                        state.getPfinishedCursorTs().minusSeconds(lookbackSeconds),
                        state.getPfinishedCursorTs(), state.getPfinishedCursorId(), PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    return false;
                }
                log.info("Analytics: folding {} process finish(es) committed behind the cursor", batch.size());
                foldProcessFinishes(batch);
                return batch.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    /** {@link #foldProcessFinishesBehindTheCursor()} for step executions. */
    private void foldStepFinishesBehindTheCursor() {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var state = state();
                var batch = source.uncountedStepFinishesBehind(
                        state.getSfinishedCursorTs().minusSeconds(lookbackSeconds),
                        state.getSfinishedCursorTs(), state.getSfinishedCursorId(), PageRequest.of(0, batchSize));
                if (batch.isEmpty()) {
                    return false;
                }
                log.info("Analytics: folding {} step finish(es) committed behind the cursor", batch.size());
                foldStepFinishes(batch);
                return batch.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    /** Takes back out what was counted for every process that has since been retried or restarted. */
    private void retractReopenedProcesses() {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                // Page 0 every time: each batch deletes its ledger rows, so the next one is new.
                var reopened = countedProcesses.reopened(PageRequest.of(0, batchSize));
                if (reopened.isEmpty()) {
                    return false;
                }
                var emptied = new ArrayList<Runnable>();
                applyProcessDeltas(RollupFolder.foldProcessFinished(
                        reopened.stream().map(AnalyticsRollupProjector::asRow).toList()), -1, emptied);
                countedProcesses.deleteAll(reopened);
                emptied.forEach(Runnable::run);
                return reopened.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
    }

    /** Takes back out what was counted for every step execution that has since been reset. */
    private void retractReopenedSteps() {
        for (int cycle = 0; cycle < maxBatchesPerCycle; cycle++) {
            var full = Boolean.TRUE.equals(tx.execute(status -> {
                var reopened = countedSteps.reopened(PageRequest.of(0, batchSize));
                if (reopened.isEmpty()) {
                    return false;
                }
                var emptied = new ArrayList<Runnable>();
                applyStepDeltas(RollupFolder.foldStepFinished(
                        reopened.stream().map(AnalyticsRollupProjector::asRow).toList()), -1, emptied);
                countedSteps.deleteAll(reopened);
                emptied.forEach(Runnable::run);
                return reopened.size() == batchSize;
            }));
            if (!full) {
                return;
            }
        }
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
                foldProcessFinishes(batch);
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
                foldStepFinishes(batch);
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

    /**
     * Folds finished processes into the rollups and the ledger: a row the ledger already holds has
     * its old contribution taken out first, so folding the same fact again changes nothing.
     */
    private void foldProcessFinishes(List<AnalyticsRollupRepositories.ProcessFinishSourceView> batch) {
        var counted = batch.stream()
                .filter(v -> v.getCreated() != null && v.getFinished() != null)
                .toList();
        var rows = counted.stream()
                .map(v -> new FinishedProcessRow(v.getWorkflowDefinitionId(),
                        v.getCreated().toLocalDate(), v.getFinished().toLocalDate(),
                        ProcessStatus.valueOf(v.getStatus()), v.getName(),
                        processDurationNanos(v.getStarted(), v.getCreated(), v.getFinished())))
                .toList();
        // Already in the ledger: reopened and finished again since it was counted (or the
        // batch is a replay). Its old contribution comes out before the new one goes in.
        var previously = countedProcesses.findAllById(counted.stream().map(v -> v.getId()).toList())
                .stream().collect(Collectors.toMap(CountedProcessEntity::getProcessId, Function.identity()));
        var emptied = new ArrayList<Runnable>();
        if (!previously.isEmpty()) {
            applyProcessDeltas(RollupFolder.foldProcessFinished(
                    previously.values().stream().map(AnalyticsRollupProjector::asRow).toList()),
                    -1, emptied);
        }
        applyProcessDeltas(RollupFolder.foldProcessFinished(rows), +1, emptied);
        for (int i = 0; i < counted.size(); i++) {
            var v = counted.get(i);
            var row = rows.get(i);
            var entry = previously.get(v.getId());
            if (entry == null) {
                countedProcesses.save(new CountedProcessEntity(v.getId(), row.definitionId(),
                        row.createdDay(), row.finishedDay(), row.status().name(), row.durationNanos()));
            } else {
                entry.setFinishedDay(row.finishedDay());
                entry.setStatus(row.status().name());
                entry.setDurationNanos(row.durationNanos());
            }
        }
        emptied.forEach(Runnable::run);
    }

    /** {@link #foldProcessFinishes} for step executions. */
    private void foldStepFinishes(List<AnalyticsRollupRepositories.StepFinishSourceView> batch) {
        var counted = batch.stream()
                .filter(v -> v.getProcessCreated() != null && v.getFinishedAt() != null)
                .toList();
        var rows = counted.stream()
                .map(v -> new FinishedStepRow(v.getWorkflowDefinitionId(), v.getStepId(),
                        v.getProcessCreated().toLocalDate(),
                        StepExecutionStatus.valueOf(v.getStatus()), v.getStepOrder(),
                        stepDurationNanos(v.getStartedAt(), v.getFinishedAt())))
                .toList();
        var previously = countedSteps.findAllById(counted.stream().map(v -> v.getId()).toList())
                .stream().collect(Collectors.toMap(CountedStepEntity::getStepExecutionId, Function.identity()));
        var emptied = new ArrayList<Runnable>();
        if (!previously.isEmpty()) {
            applyStepDeltas(RollupFolder.foldStepFinished(
                    previously.values().stream().map(AnalyticsRollupProjector::asRow).toList()),
                    -1, emptied);
        }
        applyStepDeltas(RollupFolder.foldStepFinished(rows), +1, emptied);
        for (int i = 0; i < counted.size(); i++) {
            var v = counted.get(i);
            var row = rows.get(i);
            var entry = previously.get(v.getId());
            if (entry == null) {
                countedSteps.save(new CountedStepEntity(v.getId(), row.definitionId(), row.stepId(),
                        row.createdDay(), row.status().name(), row.order(), row.durationNanos()));
            } else {
                entry.setStatus(row.status().name());
                entry.setStepOrder(row.order());
                entry.setDurationNanos(row.durationNanos());
            }
        }
        emptied.forEach(Runnable::run);
    }

    // ─────────────────────────── upserts: find the row, add (or take out) the delta, save ───────────────────────────
    //
    // sign is +1 to add a contribution, -1 to retract one the ledger says was added. A retraction
    // never creates a row (there is nothing to take out of one that is not there); a row it brings to
    // zero is queued on `emptied` and deleted once the batch's additions are in — the same key may be
    // refilled by them — so zero rows never reach the report.

    private void applyProcessDeltas(RollupFolder.ProcessFinishedDeltas deltas, int sign, List<Runnable> emptied) {
        deltas.finishedPerDay().forEach(d -> applyFinished(d, sign, emptied));
        deltas.statusCounts().forEach(d -> applyStatus(d, sign, emptied));
        deltas.durations().forEach(d -> applyProcessDuration(d, sign, emptied));
    }

    private void applyStepDeltas(RollupFolder.StepFinishedDeltas deltas, int sign, List<Runnable> emptied) {
        deltas.statusCounts().forEach(d -> applyStepStatus(d, sign, emptied));
        deltas.durations().forEach(d -> applyStepDuration(d, sign, emptied));
    }

    private void applyCreated(ProcessCreatedDaily d) {
        var key = key(d.definitionId(), String.valueOf(d.day()));
        var e = createdDaily.findById(key).orElseGet(() ->
                new ProcessCreatedDailyEntity(key, d.definitionId(), d.day(), 0));
        e.setCnt(e.getCnt() + d.count());
        createdDaily.save(e);
    }

    private void applyFinished(ProcessFinishedDaily d, int sign, List<Runnable> emptied) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()), String.valueOf(d.finishedDay()));
        var existing = finishedDaily.findById(key);
        if (existing.isEmpty() && sign < 0) {
            return;
        }
        var e = existing.orElseGet(() ->
                new ProcessFinishedDailyEntity(key, d.definitionId(), d.createdDay(), d.finishedDay(), 0));
        e.setCnt(e.getCnt() + sign * d.count());
        finishedDaily.save(e);
        if (e.getCnt() <= 0) {
            emptied.add(() -> finishedDaily.findById(key).filter(r -> r.getCnt() <= 0)
                    .ifPresent(finishedDaily::delete));
        }
    }

    private void applyStatus(ProcessStatusDaily d, int sign, List<Runnable> emptied) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()), d.status().name());
        var existing = statusDaily.findById(key);
        if (existing.isEmpty() && sign < 0) {
            return;
        }
        var e = existing.orElseGet(() ->
                new ProcessStatusDailyEntity(key, d.definitionId(), d.createdDay(), d.status().name(), 0,
                        d.anyName()));
        e.setCnt(e.getCnt() + sign * d.count());
        if (e.getAnyName() == null && d.anyName() != null) {
            e.setAnyName(d.anyName());
        }
        statusDaily.save(e);
        if (e.getCnt() <= 0) {
            emptied.add(() -> statusDaily.findById(key).filter(r -> r.getCnt() <= 0)
                    .ifPresent(statusDaily::delete));
        }
    }

    private void applyProcessDuration(ProcessDurationDaily d, int sign, List<Runnable> emptied) {
        var key = key(d.definitionId(), String.valueOf(d.createdDay()));
        var e = durationDaily.findById(key).orElse(null);
        if (e == null) {
            if (sign > 0) {
                durationDaily.save(new ProcessDurationDailyEntity(key, d.definitionId(), d.createdDay(),
                        d.samples(), d.totalNanos(), d.histogram().serialize()));
            }
            return;
        }
        var merged = DurationHistogram.parse(e.getHistogram());
        if (sign > 0) {
            merged.mergeIn(d.histogram());
        } else {
            merged.mergeOut(d.histogram());
        }
        e.setSamples(e.getSamples() + sign * d.samples());
        e.setTotalNanos(e.getTotalNanos() + sign * d.totalNanos());
        e.setHistogram(merged.serialize());
        durationDaily.save(e);
        if (e.getSamples() <= 0) {
            emptied.add(() -> durationDaily.findById(key).filter(r -> r.getSamples() <= 0)
                    .ifPresent(durationDaily::delete));
        }
    }

    private void applyStepStatus(StepStatusDaily d, int sign, List<Runnable> emptied) {
        var key = key(d.definitionId(), d.stepId(), String.valueOf(d.createdDay()), d.status().name());
        var existing = stepStatusDaily.findById(key);
        if (existing.isEmpty() && sign < 0) {
            return;
        }
        var e = existing.orElseGet(() ->
                new StepStatusDailyEntity(key, d.definitionId(), d.stepId(), d.createdDay(),
                        d.status().name(), 0, d.firstOrder()));
        e.setCnt(e.getCnt() + sign * d.count());
        if (sign > 0) {
            // A minimum is not retractable, and need not be: a retried execution keeps its _order.
            e.setFirstOrder(Math.min(e.getFirstOrder(), d.firstOrder()));
        }
        stepStatusDaily.save(e);
        if (e.getCnt() <= 0) {
            emptied.add(() -> stepStatusDaily.findById(key).filter(r -> r.getCnt() <= 0)
                    .ifPresent(stepStatusDaily::delete));
        }
    }

    private void applyStepDuration(StepDurationDaily d, int sign, List<Runnable> emptied) {
        var key = key(d.definitionId(), d.stepId(), String.valueOf(d.createdDay()));
        var e = stepDurationDaily.findById(key).orElse(null);
        if (e == null) {
            if (sign > 0) {
                stepDurationDaily.save(new StepDurationDailyEntity(key, d.definitionId(), d.stepId(),
                        d.createdDay(), d.samples(), d.totalNanos(), d.histogram().serialize()));
            }
            return;
        }
        var merged = DurationHistogram.parse(e.getHistogram());
        if (sign > 0) {
            merged.mergeIn(d.histogram());
        } else {
            merged.mergeOut(d.histogram());
        }
        e.setSamples(e.getSamples() + sign * d.samples());
        e.setTotalNanos(e.getTotalNanos() + sign * d.totalNanos());
        e.setHistogram(merged.serialize());
        stepDurationDaily.save(e);
        if (e.getSamples() <= 0) {
            emptied.add(() -> stepDurationDaily.findById(key).filter(r -> r.getSamples() <= 0)
                    .ifPresent(stepDurationDaily::delete));
        }
    }

    /** A ledger entry as the row it was folded from — what to fold, with a minus sign, to retract it. */
    private static FinishedProcessRow asRow(CountedProcessEntity c) {
        return new FinishedProcessRow(c.getWorkflowDefinitionId(), c.getCreatedDay(), c.getFinishedDay(),
                ProcessStatus.valueOf(c.getStatus()), null, c.getDurationNanos());
    }

    private static FinishedStepRow asRow(CountedStepEntity c) {
        return new FinishedStepRow(c.getWorkflowDefinitionId(), c.getStepId(), c.getCreatedDay(),
                StepExecutionStatus.valueOf(c.getStatus()), c.getStepOrder(), c.getDurationNanos());
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

    /**
     * The surrogate primary key of a rollup row, from its natural key: every part but the last
     * length-prefixed ({@code <length>:<part>:}), the last as it is — the {@link LockRowId} scheme,
     * generalised. The length says exactly where each part ends, so nothing inside a part needs
     * escaping and no two distinct keys collide; and every character is printable. The previous
     * encoding joined the parts with a NUL, which PostgreSQL refuses in any text value: every upsert
     * failed there, so the read model never folded a single fact on the database it was built for.
     * (Rows an H2 deployment wrote under the old keys stay valid — the reader sums rows by their
     * columns, never by key — and new deltas simply land in rows under the new ones.)
     */
    static String key(String... parts) {
        var key = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            var part = String.valueOf(parts[i]);
            key.append(part.length()).append(':').append(part).append(':');
        }
        return key.append(parts[parts.length - 1]).toString();
    }
}
