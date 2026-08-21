package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.LocalDate;
import java.util.List;

/**
 * What the analytics report is made of, already reduced.
 *
 * <p>Every field of a report is a {@code GROUP BY}: counts by status, counts per day, a duration
 * average and a percentile. The engine used to answer them by loading every process and every step
 * execution in the window and folding them in Java — 383 215 rows on the deployment this was
 * measured against, to put about fifty on screen. These types are the shape of the answer instead
 * of the shape of the data, so a store that can group can return tens of rows.
 *
 * <p>The in-memory store computes them exactly as the service used to; the JPA store computes them
 * in SQL. Both are held to the same numbers by an equivalence test, which is what makes that
 * division safe.
 *
 * @see ProcessRepository#aggregateProcesses
 * @see StepExecutionRepository#aggregateSteps
 */
public final class AnalyticsAggregates {

    private AnalyticsAggregates() {
    }

    /**
     * A duration distribution, in the two numbers a report shows, plus the count behind them.
     *
     * <p>Carried as a total rather than an average so the division happens in one place — the
     * service divides exactly as {@link java.time.Duration#dividedBy} always did, and an average
     * computed in SQL as a {@code double} cannot drift from one computed in Java as integer
     * nanoseconds. Nanoseconds because the alternative truncates: a step that takes four
     * milliseconds is a zero in any coarser unit, and most of them do.
     *
     * @param samples  how many measurements there were; zero means the other two are null
     * @param totalNanos the sum of them
     * @param p95Nanos nearest-rank 95th percentile — the sample at {@code ceil(0.95n)}, not an
     *                 interpolation, which is what {@code percentile_disc} returns and what the
     *                 Java implementation has always picked
     */
    public record DurationAggregate(long samples, Long totalNanos, Long p95Nanos) {

        public static final DurationAggregate NONE = new DurationAggregate(0, null, null);
    }

    /** How many processes of one definition ended in one status, and a name to fall back on. */
    public record DefinitionStatusCount(String workflowDefinitionId, ProcessStatus status, long count,
                                        String anyProcessName) {
    }

    /** How many processes of one definition fall on one day, by whichever timestamp was asked for. */
    public record DefinitionDayCount(String workflowDefinitionId, LocalDate day, long count) {
    }

    /** How long the finished processes of one definition took. */
    public record DefinitionDuration(String workflowDefinitionId, DurationAggregate duration) {
    }

    /**
     * How many executions of one step of one definition ended in one status.
     *
     * @param firstOrder the lowest {@code _order} seen for this step, which is the order the report
     *                   lists steps in — flow order, as the engine ran them
     */
    public record DefinitionStepCount(String workflowDefinitionId, String stepId,
                                      StepExecutionStatus status, long count, long firstOrder) {
    }

    /** How long the finished executions of one step of one definition took. */
    public record DefinitionStepDuration(String workflowDefinitionId, String stepId,
                                         DurationAggregate duration) {
    }

    /** Everything the process store can reduce for one window. */
    public record ProcessAggregates(List<DefinitionStatusCount> statusCounts,
                                    List<DefinitionDayCount> createdPerDay,
                                    List<DefinitionDayCount> finishedPerDay,
                                    List<DefinitionDuration> durations) {

        public static final ProcessAggregates EMPTY =
                new ProcessAggregates(List.of(), List.of(), List.of(), List.of());
    }

    /** Everything the step-execution store can reduce for one window. */
    public record StepAggregates(List<DefinitionStepCount> counts,
                                 List<DefinitionStepDuration> durations) {

        public static final StepAggregates EMPTY = new StepAggregates(List.of(), List.of());
    }
}
