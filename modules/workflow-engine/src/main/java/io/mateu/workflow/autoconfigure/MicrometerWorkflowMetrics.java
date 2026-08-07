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
    public static final String COMPENSATIONS_FAILED = "eventconductor.compensations.failed";
    public static final String CONCURRENT_WRITES_REJECTED = "eventconductor.process.concurrent.writes.rejected";
    public static final String EVENTS_DEAD_LETTERED = "eventconductor.events.dead.lettered";

    public static final String STALLED_STEPS = "eventconductor.steps.stalled";
    public static final String PROCESSES_RUNNING = "eventconductor.process.running";
    public static final String OUTBOX_PENDING = "eventconductor.outbox.pending";

    public static final String OUTBOX_PICKUP_LATENCY = "eventconductor.outbox.pickup.latency";
    public static final String OUTBOX_BATCH_DELIVER = "eventconductor.outbox.batch.deliver";
    public static final String OUTBOX_BATCH_SIZE = "eventconductor.outbox.batch.size";
    public static final String OUTBOX_RELAY_DRAINING = "eventconductor.outbox.relay.draining";
    public static final String OUTBOX_RELAY_WAITING = "eventconductor.outbox.relay.waiting";

    public static final String TAG_WORKFLOW_DEFINITION_ID = "workflowDefinitionId";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_TRIGGER = "trigger";

    private static final String UNKNOWN = "unknown";

    private final java.util.concurrent.atomic.AtomicLong stalledSteps = new java.util.concurrent.atomic.AtomicLong();

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

    @Override
    public void compensationFailed(String workflowDefinitionId) {
        Counter.builder(COMPENSATIONS_FAILED)
                .description("Saga rollbacks that could not complete: a compensation step itself failed, "
                        + "leaving the process partially rolled back (COMPENSATION_FAILED)")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
    }

    @Override
    public void concurrentWriteRejected(String processId) {
        Counter.builder(CONCURRENT_WRITES_REJECTED)
                .description("Writes rejected by optimistic locking because another writer had the process")
                .register(registry)
                .increment();
    }

    @Override
    public void eventDeadLettered(String source) {
        Counter.builder(EVENTS_DEAD_LETTERED)
                .description("Events parked on the dead-letter destination as unprocessable")
                .tag("source", tagValue(source))
                .register(registry)
                .increment();
    }

    @Override
    public void outboxMessageRelayed(Duration ageAtClaim) {
        if (ageAtClaim == null || ageAtClaim.isNegative()) {
            return;
        }
        Timer.builder(OUTBOX_PICKUP_LATENCY)
                .description("Time a message waited in the outbox between commit and being claimed by a relay. "
                        + "Expected to be bimodal across pods: rows this pod wrote are signalled, rows written "
                        + "elsewhere wait for the poll")
                .publishPercentileHistogram()
                .register(registry)
                .record(ageAtClaim);
    }

    @Override
    public void outboxBatchDelivered(int messages, Duration inDeliver) {
        io.micrometer.core.instrument.DistributionSummary.builder(OUTBOX_BATCH_SIZE)
                .description("Messages claimed in one relay batch")
                .register(registry)
                .record(messages);
        if (inDeliver != null && !inDeliver.isNegative()) {
            Timer.builder(OUTBOX_BATCH_DELIVER)
                    .description("Time spent inside the sends of one relay batch. With synchronous sends this is "
                            + "messages x broker ack latency, paid in series on the single relay thread")
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(inDeliver);
        }
    }

    @Override
    public void outboxRelayCycle(Duration draining, Duration waiting) {
        record(OUTBOX_RELAY_DRAINING, "Time the relay spent draining in one cycle. Its ratio to the waiting "
                + "timer is the relay's duty cycle; near 1 means the single relay thread is the ceiling", draining);
        record(OUTBOX_RELAY_WAITING, "Time the relay spent waiting for work in one cycle, either signalled by "
                + "this pod's own commit or timed out on the poll interval", waiting);
    }

    private void record(String name, String description, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder(name).description(description).register(registry).record(duration);
    }

    @jakarta.annotation.PostConstruct
    void registerGauges() {
        io.micrometer.core.instrument.Gauge
                .builder(STALLED_STEPS, stalledSteps, java.util.concurrent.atomic.AtomicLong::doubleValue)
                .description("Live step executions with no deadline that nothing will ever time out")
                .register(registry);
    }

    /**
     * A gauge, not a counter: the question is how many steps are stuck right now, and a counter
     * of observations would answer a question nobody asked. The registry holds the reference and
     * reads it when scraped, so the scheduler's loop just writes the latest count.
     */
    @Override
    public void stalledStepsObserved(long count) {
        stalledSteps.set(count);
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
