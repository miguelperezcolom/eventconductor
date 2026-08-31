package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

/**
 * Micrometer-backed {@link WorkflowMetrics}. Meters are created lazily per tag
 * combination; the registry caches them, so repeated calls are cheap.
 *
 * <p>The {@link MeterRegistry} is resolved on first use, not when this object is built. The bean is
 * wired early — the engine's outbox relay and process lifecycle depend on it — and asking for the
 * registry then can lose the race and get {@code null} (the composite is not primary yet), which is
 * exactly how the counters and timers silently fell back to no-ops. By the time the first process
 * starts or the relay turns, the registry is there; until it is (or if there is none at all, in an
 * app without Actuator), every method is a no-op.
 */
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
    public static final String STALLED_PROCESSES = "eventconductor.processes.stalled";
    public static final String PROCESSES_RUNNING = "eventconductor.process.running";
    public static final String OUTBOX_PENDING = "eventconductor.outbox.pending";

    public static final String OUTBOX_PICKUP_LATENCY = "eventconductor.outbox.pickup.latency";
    public static final String OUTBOX_BATCH_DELIVER = "eventconductor.outbox.batch.deliver";
    public static final String OUTBOX_BATCH_SIZE = "eventconductor.outbox.batch.size";
    public static final String OUTBOX_RELAY_DRAINING = "eventconductor.outbox.relay.draining";
    public static final String OUTBOX_RELAY_WAITING = "eventconductor.outbox.relay.waiting";
    public static final String OUTBOX_RELAY_STALLED = "eventconductor.outbox.relay.stalled";

    public static final String TAG_WORKFLOW_DEFINITION_ID = "workflowDefinitionId";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_TRIGGER = "trigger";

    private static final String UNKNOWN = "unknown";

    private final java.util.concurrent.atomic.AtomicLong stalledSteps = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean stalledGaugeRegistered;
    private final java.util.concurrent.atomic.AtomicLong stalledProcesses = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean stalledProcessGaugeRegistered;

    private final java.util.function.Supplier<MeterRegistry> registrySupplier;
    private volatile MeterRegistry resolvedRegistry;

    /** Eager: the registry is already in hand (tests, and the case where wiring order happens to work). */
    public MicrometerWorkflowMetrics(MeterRegistry registry) {
        this.registrySupplier = () -> registry;
    }

    /** Lazy: the registry is resolved on first use, dodging the bean-creation-order race. */
    public MicrometerWorkflowMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        this.registrySupplier = registryProvider::getIfAvailable;
    }

    /** The registry once it exists, else {@code null}. Resolves each call until one appears, then caches. */
    private MeterRegistry registry() {
        var r = resolvedRegistry;
        if (r == null) {
            r = registrySupplier.get();
            if (r != null) {
                resolvedRegistry = r;
            }
        }
        return r;
    }

    @Override
    public void processStarted(String workflowDefinitionId) {
        var registry = registry();
        if (registry == null) return;
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
        var registry = registry();
        if (registry == null) return;
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
        var registry = registry();
        if (registry == null) return;
        Counter.builder(STEP_RETRIES)
                .description("Step execution retries performed")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .tag(TAG_TRIGGER, trigger != null ? trigger.name().toLowerCase() : UNKNOWN)
                .register(registry)
                .increment();
    }

    @Override
    public void compensationTriggered(String workflowDefinitionId) {
        var registry = registry();
        if (registry == null) return;
        Counter.builder(STEP_COMPENSATIONS)
                .description("Compensation steps triggered after retries were exhausted")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
    }

    @Override
    public void compensationFailed(String workflowDefinitionId) {
        var registry = registry();
        if (registry == null) return;
        Counter.builder(COMPENSATIONS_FAILED)
                .description("Saga rollbacks that could not complete: a compensation step itself failed, "
                        + "leaving the process partially rolled back (COMPENSATION_FAILED)")
                .tag(TAG_WORKFLOW_DEFINITION_ID, tagValue(workflowDefinitionId))
                .register(registry)
                .increment();
    }

    @Override
    public void concurrentWriteRejected(String processId) {
        var registry = registry();
        if (registry == null) return;
        Counter.builder(CONCURRENT_WRITES_REJECTED)
                .description("Writes rejected by optimistic locking because another writer had the process")
                .register(registry)
                .increment();
    }

    @Override
    public void eventDeadLettered(String source) {
        var registry = registry();
        if (registry == null) return;
        Counter.builder(EVENTS_DEAD_LETTERED)
                .description("Events parked on the dead-letter destination as unprocessable")
                .tag("source", tagValue(source))
                .register(registry)
                .increment();
    }

    @Override
    public void outboxMessageRelayed(Duration ageAtClaim) {
        var registry = registry();
        if (registry == null || ageAtClaim == null || ageAtClaim.isNegative()) {
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
        var registry = registry();
        if (registry == null) return;
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

    @Override
    public void outboxRelayStalled() {
        var registry = registry();
        if (registry == null) {
            return;
        }
        Counter.builder(OUTBOX_RELAY_STALLED)
                .description("Relay passes that claimed outbox rows and settled none of them. Anything "
                        + "above zero means committed messages are not reaching the broker")
                .register(registry)
                .increment();
    }

    private void record(String name, String description, Duration duration) {
        var registry = registry();
        if (registry == null || duration == null || duration.isNegative()) {
            return;
        }
        Timer.builder(name).description(description).register(registry).record(duration);
    }

    /**
     * A gauge, not a counter: the question is how many steps are stuck right now, and a counter
     * of observations would answer a question nobody asked. Registered on the first observation
     * rather than at construction, so it lands on the real registry once it exists rather than
     * being lost to a not-yet-resolved one. The registry holds the reference and reads it when
     * scraped, so the scheduler's loop just writes the latest count.
     */
    @Override
    public void stalledStepsObserved(long count) {
        stalledSteps.set(count);
        if (!stalledGaugeRegistered) {
            var registry = registry();
            if (registry != null) {
                io.micrometer.core.instrument.Gauge
                        .builder(STALLED_STEPS, stalledSteps, java.util.concurrent.atomic.AtomicLong::doubleValue)
                        .description("Live step executions with no deadline that nothing will ever time out")
                        .register(registry);
                stalledGaugeRegistered = true;
            }
        }
    }

    /** Same shape as the step gauge above, and deliberately a separate series: see the port. */
    @Override
    public void stalledProcessesObserved(long count) {
        stalledProcesses.set(count);
        if (!stalledProcessGaugeRegistered) {
            var registry = registry();
            if (registry != null) {
                io.micrometer.core.instrument.Gauge
                        .builder(STALLED_PROCESSES, stalledProcesses, java.util.concurrent.atomic.AtomicLong::doubleValue)
                        .description("Running processes with no step left to run and no deadline anywhere")
                        .register(registry);
                stalledProcessGaugeRegistered = true;
            }
        }
    }

    private void processFinished(String counterName, String description, String outcome,
                                 String workflowDefinitionId, Duration duration) {
        var registry = registry();
        if (registry == null) return;
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
