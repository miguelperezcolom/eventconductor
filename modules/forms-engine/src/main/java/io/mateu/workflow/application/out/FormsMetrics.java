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
     * Something happened to a task — {@code created}, {@code assigned}, {@code claimed},
     * {@code released}, {@code completed}, {@code cancelled}, {@code escalated} —
     * {@code eventconductor.tasks.events}, tagged with the event and the form.
     */
    default void taskEvent(String event, String formId) {}

    /**
     * How long a task has been open: from its creation to now (UTC), or null for a task that does not
     * know when it was created (one from before tasks recorded it).
     */
    static Duration durationOf(FormExecution execution) {
        if (execution == null || execution.createdAt() == null) {
            return null;
        }
        return Duration.between(execution.createdAt(), io.mateu.workflow.application.services.TaskInboxOrder.nowUtc());
    }
}
