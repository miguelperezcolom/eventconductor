package io.mateu.workflow.application.out.analytics;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDate;

/**
 * The shapes the analytics read model is stored and folded in — one record per rollup table, plus
 * the small rows the projector folds from and the live overlay reads.
 *
 * <p>Every rollup row is keyed by the process's <b>creation day</b>, because that is what the report
 * selects a window by. Everything a window asks for is then a sum over the creation-days it covers:
 * counts add, samples and totals add, {@link DurationHistogram}s merge. The one exception is
 * {@link ProcessFinishedDaily}, which carries a second day — the finish day — because "throughput"
 * is finished-processes-per-day among the processes a window selected, and that is a cross of the
 * two dates.
 *
 * <p>These are the read model's own types, deliberately not the {@code AnalyticsAggregates} the
 * service consumes: the folder produces these, the reducer turns these back into aggregates, and
 * keeping the two vocabularies apart is what lets the folder be tested without a database and the
 * reducer without a projector.
 */
public final class RollupModel {

    private RollupModel() {
    }

    // ─────────────────────────── stored rollup rows (one per table) ───────────────────────────

    /** Processes of a definition created on a day — every one, whatever its status became. */
    public record ProcessCreatedDaily(String definitionId, LocalDate day, long count) {
    }

    /** Processes created on {@code createdDay} that finished on {@code finishedDay}. */
    public record ProcessFinishedDaily(String definitionId, LocalDate createdDay,
                                       LocalDate finishedDay, long count) {
    }

    /** Finished processes of a definition created on a day, by the status they ended in. */
    public record ProcessStatusDaily(String definitionId, LocalDate createdDay, ProcessStatus status,
                                     long count, String anyName) {
    }

    /** How long the finished processes created on a day took. */
    public record ProcessDurationDaily(String definitionId, LocalDate createdDay, long samples,
                                       long totalNanos, DurationHistogram histogram) {
    }

    /** Finished executions of a step, scoped to processes created on a day, by ending status. */
    public record StepStatusDaily(String definitionId, String stepId, LocalDate createdDay,
                                  StepExecutionStatus status, long count, long firstOrder) {
    }

    /** How long the finished executions of a step took, scoped to processes created on a day. */
    public record StepDurationDaily(String definitionId, String stepId, LocalDate createdDay,
                                    long samples, long totalNanos, DurationHistogram histogram) {
    }

    // ─────────────────────────── rows the projector folds from ───────────────────────────

    /** One process, the moment it is counted as created. */
    public record CreatedRow(String definitionId, LocalDate createdDay) {
    }

    /** One process, the moment it finished — immutable from then, which is what makes folding safe. */
    public record FinishedProcessRow(String definitionId, LocalDate createdDay, LocalDate finishedDay,
                                     ProcessStatus status, String name, Long durationNanos) {
    }

    /** One step execution, the moment it finished. {@code durationNanos} null when it never started. */
    public record FinishedStepRow(String definitionId, String stepId, LocalDate createdDay,
                                  StepExecutionStatus status, long order, Long durationNanos) {
    }

    // ─────────────────────────── live overlay (in-flight, read straight) ───────────────────────────

    /** A count of not-yet-finished processes, read live at report time and merged over the rollup. */
    public record LiveProcessStatus(String definitionId, ProcessStatus status, long count, String anyName) {
    }

    /** A count of not-yet-finished step executions, read live at report time. */
    public record LiveStepStatus(String definitionId, String stepId, StepExecutionStatus status,
                                 long count, long firstOrder) {
    }
}
