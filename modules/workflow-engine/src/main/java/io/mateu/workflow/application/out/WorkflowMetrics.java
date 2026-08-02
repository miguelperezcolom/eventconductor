package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Outgoing port for engine observability metrics.
 *
 * All methods are no-ops by default so the engine runs unchanged when no metrics
 * backend is configured. When Micrometer and a {@code MeterRegistry} bean are
 * present, {@code WorkflowMetricsAutoConfiguration} provides an implementation
 * that publishes these signals as Micrometer meters.
 *
 * Instrumented at the use-case layer, so the same metrics are emitted in all
 * deployment modes (embedded+memory, embedded+jpa, kafka+jpa).
 */
public interface WorkflowMetrics {

    WorkflowMetrics NOOP = new WorkflowMetrics() {};

    default void processStarted(String workflowDefinitionId) {}

    default void processCompleted(String workflowDefinitionId, Duration duration) {}

    default void processErrored(String workflowDefinitionId, Duration duration) {}

    default void processCancelled(String workflowDefinitionId, Duration duration) {}

    default void stepExecutionFinished(String workflowDefinitionId, StepExecutionStatus outcome, Duration duration) {}

    default void retryPerformed(String workflowDefinitionId, RetryTrigger trigger) {}

    default void compensationTriggered(String workflowDefinitionId) {}

    /**
     * A write lost an optimistic-locking race: two writers touched the same process.
     *
     * <p>Worth watching rather than merely logging. Events are keyed by process and a consumer
     * group gives each partition to one consumer, so in steady state this must be flat at zero;
     * the only expected source is the brief window of a rebalance, when the outgoing pod may
     * still be finishing a record the incoming one now owns. A non-zero rate outside rebalances
     * means something is reaching a process from outside its partition — which is exactly what
     * has to be true before the pessimistic lock can go.
     */
    default void concurrentWriteRejected(String processId) {}

    /**
     * An event was parked because the engine will never be able to process it.
     *
     * <p>The one counter here that should trigger someone looking: a retry is the engine coping,
     * a dead letter is the engine giving up on a specific event and saying so.
     */
    default void eventDeadLettered(String source) {}

    /**
     * How many live steps are waiting with no deadline that could ever fire.
     *
     * <p>The one gauge to alert on. Everything else here counts things happening; this counts
     * things that have stopped happening and that nothing in the engine will notice — a step
     * whose dispatch or whose worker reply was lost, on a step that declares no timeout, waits
     * forever and is invisible to the deadline scan by construction. Any sustained non-zero
     * value is work that will never finish.
     */
    default void stalledStepsObserved(long count) {}

    enum RetryTrigger { AUTO, MANUAL }

    /**
     * Wall-clock duration of a process, best effort: from started (falling back to
     * created) until finished (falling back to now). Null when the process has no
     * usable start timestamp.
     */
    static Duration durationOf(Process process) {
        var start = process.getStarted() != null ? process.getStarted() : process.getCreated();
        if (start == null) {
            return null;
        }
        var end = process.getFinished() != null ? process.getFinished() : LocalDateTime.now();
        return Duration.between(start, end);
    }
}
