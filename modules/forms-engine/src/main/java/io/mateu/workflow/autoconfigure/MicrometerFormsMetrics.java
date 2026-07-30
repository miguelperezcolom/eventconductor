package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.FormsMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import java.time.Duration;

/**
 * Micrometer-backed {@link FormsMetrics}. Meters are created lazily per tag
 * combination; the registry caches them, so repeated calls are cheap.
 *
 * Only instantiated by {@code FormsMetricsAutoConfiguration} when Micrometer is
 * on the classpath and a {@code MeterRegistry} bean exists — do not reference this
 * class from code that must run without Micrometer.
 */
@RequiredArgsConstructor
public class MicrometerFormsMetrics implements FormsMetrics {

    public static final String TASKS_CREATED = "eventconductor.forms.task.created";
    public static final String TASKS_COMPLETED = "eventconductor.forms.task.completed";
    public static final String TASKS_CANCELLED = "eventconductor.forms.task.cancelled";
    public static final String TASK_DURATION = "eventconductor.forms.task.duration";
    public static final String FORMS_IMPORTED = "eventconductor.forms.imported";

    public static final String TAG_FORM_ID = "formId";
    public static final String TAG_OUTCOME = "outcome";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    @Override
    public void taskCreated(String formId) {
        Counter.builder(TASKS_CREATED)
                .description("Form tasks created")
                .tag(TAG_FORM_ID, tagValue(formId))
                .register(registry)
                .increment();
    }

    @Override
    public void taskCompleted(String formId, Duration duration) {
        taskFinished(TASKS_COMPLETED, "Form tasks completed", "completed", formId, duration);
    }

    @Override
    public void taskCancelled(String formId, Duration duration) {
        taskFinished(TASKS_CANCELLED, "Form tasks cancelled", "cancelled", formId, duration);
    }

    @Override
    public void formsImported(long count) {
        Counter.builder(FORMS_IMPORTED)
                .description("Form definitions imported from git")
                .register(registry)
                .increment(count);
    }

    private void taskFinished(String counterName, String description, String outcome,
                              String formId, Duration duration) {
        Counter.builder(counterName)
                .description(description)
                .tag(TAG_FORM_ID, tagValue(formId))
                .register(registry)
                .increment();
        if (duration != null && !duration.isNegative()) {
            Timer.builder(TASK_DURATION)
                    .description("Form task duration, from creation to final status")
                    .tag(TAG_FORM_ID, tagValue(formId))
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
                    .record(duration);
        }
    }

    private static String tagValue(String value) {
        return value != null && !value.isBlank() ? value : UNKNOWN;
    }
}
