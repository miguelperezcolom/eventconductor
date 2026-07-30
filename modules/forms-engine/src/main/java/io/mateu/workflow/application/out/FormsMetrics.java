package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.FormExecution;

import java.time.Duration;

/**
 * Outgoing port for forms observability metrics.
 *
 * All methods are no-ops by default so the engine runs unchanged when no metrics
 * backend is configured. When Micrometer and a {@code MeterRegistry} bean are
 * present, {@code FormsMetricsAutoConfiguration} provides an implementation
 * that publishes these signals as Micrometer meters.
 *
 * Instrumented at the use-case layer, so the same metrics are emitted in all
 * deployment modes (embedded+memory, embedded+jpa, kafka+jpa).
 */
public interface FormsMetrics {

    FormsMetrics NOOP = new FormsMetrics() {};

    default void taskCreated(String formId) {}

    default void taskCompleted(String formId, Duration duration) {}

    default void taskCancelled(String formId, Duration duration) {}

    default void formsImported(long count) {}

    /**
     * Wall-clock duration of a form execution, best effort. {@link FormExecution}
     * currently carries no lifecycle timestamps, so there is no usable start time
     * and this returns null. Kept as a hook mirroring the workflow engine's
     * {@code WorkflowMetrics.durationOf}.
     */
    static Duration durationOf(FormExecution execution) {
        return null;
    }
}
