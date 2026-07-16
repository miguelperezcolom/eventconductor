package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

import java.time.Duration;

/**
 * Micrometer-backed {@link WorkflowMetrics}. Meters are created lazily per tag
 * combination; the registry caches them, so repeated calls are cheap.
 *
 * Only instantiated by {@code WorkflowMetricsAutoConfiguration} when Micrometer is
 * on the classpath and a {@code MeterRegistry} bean exists — do not reference this
 * class from code that must run without Micrometer.
 */
@RequiredArgsConstructor
public class MicrometerWorkflowMetrics implements WorkflowMetrics {

    public static final String PROCESSES_STARTED = "eventconductor.process.started";
    public static final String PROCESSES_COMPLETED = "eventconductor.process.completed";
    public static final String PROCESSES_ERRORED = "eventconductor.process.errored";
    public static final String PROCESSES_CANCELLED = "eventconductor.process.cancelled";
    public static final String PROCESS_DURATION = "eventconductor.process.duration";
    public static final String STEP_EXECUTIONS = "eventconductor.step.executions";
    public static final String STEP_DURATION = "eventconductor.step.duration";
    public static final String STEP_RETRIES = "eventconductor.step.retries";
    public static final String STEP_COMPENSATIONS = "eventconductor.step.compensations";
    public static final String PROCESSES_RUNNING = "eventconductor.process.running";
    public static final String OUTBOX_PENDING = "eventconductor.outbox.pending";

    public static final String TAG_WORKFLOW_DEFINITION_ID = "workflowDefinitionId";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_TRIGGER = "trigger";

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    @Override
    public void processStarted(String workflowDefinitionId) {
        Counter.builder(PROCESSES_STARTED)
                .description("Workflow processes started")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
    }

    @Override
    public void processCompleted(String workflowDefinitionId, Duration duration) {
        processFinished(PROCESSES_COMPLETED, "Workflow processes completed", "COMPLETED", workflowDefinitionId, duration);
    }

    @Override
    public void processErrored(String workflowDefinitionId, Duration duration) {
        processFinished(PROCESSES_ERRORED, "Workflow processes finished in error", "ERROR", workflowDefinitionId, duration);
    }

    @Override
    public void processCancelled(String workflowDefinitionId, Duration duration) {
        processFinished(PROCESSES_CANCELLED, "Workflow processes cancelled", "CANCELLED", workflowDefinitionId, duration);
    }

    @Override
    public void stepExecutionFinished(String workflowDefinitionId, StepExecutionStatus outcome, Duration duration) {
        var outcomeTag = outcome != null ? outcome.name() : UNKNOWN;
        Counter.builder(STEP_EXECUTIONS)
                .description("Step executions finished, by outcome")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .tag(TAG_OUTCOME, outcomeTag)
                .register(registry)
                .increment();
        if (duration != null && !duration.isNegative()) {
            Timer.builder(STEP_DURATION)
                    .description("Step execution duration, from dispatch to final status")
                    .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                    .tag(TAG_OUTCOME, outcomeTag)
                    .register(registry)
                    .record(duration);
        }
    }

    @Override
    public void retryPerformed(String workflowDefinitionId, RetryTrigger trigger) {
        Counter.builder(STEP_RETRIES)
                .description("Step execution retries performed")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .tag(TAG_TRIGGER, trigger != null ? trigger.name().toLowerCase() : UNKNOWN)
                .register(registry)
                .increment();
    }

    @Override
    public void compensationTriggered(String workflowDefinitionId) {
        Counter.builder(STEP_COMPENSATIONS)
                .description("Compensation steps triggered after retries were exhausted")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
    }

    private void processFinished(String counterName, String description, String outcome,
                                 String workflowDefinitionId, Duration duration) {
        Counter.builder(counterName)
                .description(description)
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
        if (duration != null && !duration.isNegative()) {
            Timer.builder(PROCESS_DURATION)
                    .description("Workflow process duration, from start to final status")
                    .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                    .tag(TAG_OUTCOME, outcome)
                    .register(registry)
                    .record(duration);
        }
    }

    private static String tagValue(String value) {
        return value != null && !value.isBlank() ? value : UNKNOWN;
    }
}
