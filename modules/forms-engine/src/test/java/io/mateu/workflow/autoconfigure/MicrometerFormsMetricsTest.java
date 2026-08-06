package io.mateu.workflow.autoconfigure;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The forms meters, which nothing exercised.
 *
 * <p>Meter names are part of the 1.0 contract — see the [Observability](observability) reference —
 * and their consumers are outside the build: a Grafana dashboard, a Prometheus alert rule, a
 * PagerDuty route. Renaming one breaks all of them and breaks nothing here, so the names are
 * asserted as **literals** rather than through the constants. A test written against
 * {@code MicrometerFormsMetrics.TASKS_CREATED} passes whatever that constant is changed to, which
 * is exactly the change worth catching.
 */
class MicrometerFormsMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerFormsMetrics metrics = new MicrometerFormsMetrics(registry);

    @Test
    void theMeterNamesAndTagsAreTheOnesTheDocumentationPromises() {
        metrics.taskCreated("form-1");
        metrics.taskCompleted("form-1", Duration.ofSeconds(1));
        metrics.taskCancelled("form-1", Duration.ofSeconds(1));
        metrics.formsImported(1);

        assertThat(registry.get("eventconductor.forms.task.created").tag("formId", "form-1").counter())
                .isNotNull();
        assertThat(registry.get("eventconductor.forms.task.completed").tag("formId", "form-1").counter())
                .isNotNull();
        assertThat(registry.get("eventconductor.forms.task.cancelled").tag("formId", "form-1").counter())
                .isNotNull();
        assertThat(registry.get("eventconductor.forms.task.duration")
                .tag("formId", "form-1").tag("outcome", "completed").timer()).isNotNull();
        assertThat(registry.get("eventconductor.forms.imported").counter()).isNotNull();
    }

    @Test
    void tasksAreCountedPerForm() {
        metrics.taskCreated("form-1");
        metrics.taskCreated("form-1");
        metrics.taskCreated("form-2");

        assertThat(registry.get("eventconductor.forms.task.created")
                .tag("formId", "form-1").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.forms.task.created")
                .tag("formId", "form-2").counter().count()).isEqualTo(1);
    }

    @Test
    void completedAndCancelledShareTheDurationTimerAndSeparateOnOutcome() {
        metrics.taskCompleted("form-1", Duration.ofSeconds(3));
        metrics.taskCancelled("form-1", Duration.ofSeconds(5));

        var completed = registry.get("eventconductor.forms.task.duration")
                .tag("outcome", "completed").timer();
        var cancelled = registry.get("eventconductor.forms.task.duration")
                .tag("outcome", "cancelled").timer();

        assertThat(completed.count()).isEqualTo(1);
        assertThat(completed.totalTime(TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(cancelled.count()).isEqualTo(1);
        assertThat(cancelled.totalTime(TimeUnit.SECONDS)).isEqualTo(5);
    }

    /**
     * A form execution without usable timestamps yields no duration, and a clock that went
     * backwards yields a negative one. Recording either would poison the timer's distribution for
     * everything else on that form, so both are counted and neither is timed.
     */
    @Test
    void aMissingOrNegativeDurationStillCountsTheTaskButRecordsNoTime() {
        metrics.taskCompleted("form-1", null);
        metrics.taskCompleted("form-1", Duration.ofSeconds(-1));

        assertThat(registry.get("eventconductor.forms.task.completed").counter().count()).isEqualTo(2);
        assertThat(registry.find("eventconductor.forms.task.duration").timer()).isNull();
    }

    /**
     * A null or blank tag value is not allowed to reach Micrometer: a null tag throws and takes the
     * caller down with it, and metrics must never be the thing that fails a form.
     */
    @Test
    void anAbsentFormIdBecomesUnknownRatherThanThrowing() {
        metrics.taskCreated(null);
        metrics.taskCreated("   ");

        assertThat(registry.get("eventconductor.forms.task.created")
                .tag("formId", "unknown").counter().count()).isEqualTo(2);
    }

    @Test
    void importedCountsDefinitionsNotBatches() {
        metrics.formsImported(7);
        metrics.formsImported(3);

        assertThat(registry.get("eventconductor.forms.imported").counter().count()).isEqualTo(10);
    }
}
