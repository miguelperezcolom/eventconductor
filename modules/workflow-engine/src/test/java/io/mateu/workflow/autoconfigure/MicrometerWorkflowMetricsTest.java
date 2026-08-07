package io.mateu.workflow.autoconfigure;

import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class MicrometerWorkflowMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerWorkflowMetrics metrics = new MicrometerWorkflowMetrics(registry);

    @Test
    void processStartedIncrementsCounterTaggedByWorkflowDefinitionId() {
        metrics.processStarted("wd-1");
        metrics.processStarted("wd-1");
        metrics.processStarted("wd-2");

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_STARTED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "wd-1")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_STARTED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "wd-2")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void processCompletedIncrementsCounterAndRecordsDuration() {
        metrics.processCompleted("wd-1", Duration.ofSeconds(3));

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_COMPLETED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "wd-1")
                .counter().count()).isEqualTo(1);
        var timer = registry.get(MicrometerWorkflowMetrics.PROCESS_DURATION)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "wd-1")
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "COMPLETED")
                .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3);
    }

    @Test
    void processErroredAndCancelledUseTheirOwnCountersAndOutcomeTags() {
        metrics.processErrored("wd-1", Duration.ofSeconds(1));
        metrics.processCancelled("wd-1", Duration.ofSeconds(2));

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_ERRORED).counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_CANCELLED).counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESS_DURATION)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "ERROR").timer().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESS_DURATION)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "CANCELLED").timer().count()).isEqualTo(1);
    }

    @Test
    void nullDurationCountsButRecordsNoTimer() {
        metrics.processCompleted("wd-1", null);

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_COMPLETED).counter().count()).isEqualTo(1);
        assertThat(registry.find(MicrometerWorkflowMetrics.PROCESS_DURATION).timer()).isNull();
    }

    @Test
    void stepExecutionFinishedCountsByOutcomeAndRecordsDuration() {
        metrics.stepExecutionFinished("wd-1", StepExecutionStatus.COMPLETED, Duration.ofMillis(500));
        metrics.stepExecutionFinished("wd-1", StepExecutionStatus.ERROR, Duration.ofMillis(100));
        metrics.stepExecutionFinished("wd-1", StepExecutionStatus.TIMEOUT, null);

        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_EXECUTIONS)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "COMPLETED").counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_EXECUTIONS)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "ERROR").counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_EXECUTIONS)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "TIMEOUT").counter().count()).isEqualTo(1);
        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_DURATION)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "COMPLETED").timer().count()).isEqualTo(1);
        // TIMEOUT was reported without a duration, so no timer sample exists for it.
        assertThat(registry.find(MicrometerWorkflowMetrics.STEP_DURATION)
                .tag(MicrometerWorkflowMetrics.TAG_OUTCOME, "TIMEOUT").timer()).isNull();
    }

    @Test
    void retriesAreTaggedByTrigger() {
        metrics.retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.AUTO);
        metrics.retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.AUTO);
        metrics.retryPerformed("wd-1", WorkflowMetrics.RetryTrigger.MANUAL);

        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_RETRIES)
                .tag(MicrometerWorkflowMetrics.TAG_TRIGGER, "auto").counter().count()).isEqualTo(2);
        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_RETRIES)
                .tag(MicrometerWorkflowMetrics.TAG_TRIGGER, "manual").counter().count()).isEqualTo(1);
    }

    @Test
    void compensationsAreCounted() {
        metrics.compensationTriggered("wd-1");

        assertThat(registry.get(MicrometerWorkflowMetrics.STEP_COMPENSATIONS)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "wd-1")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void nullOrBlankWorkflowDefinitionIdBecomesUnknownTag() {
        metrics.processStarted(null);
        metrics.processStarted("  ");

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_STARTED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "unknown")
                .counter().count()).isEqualTo(2);
    }

    @Test
    void negativeDurationIsIgnored() {
        metrics.processCompleted("wd-1", Duration.ofSeconds(-5));

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_COMPLETED).counter().count()).isEqualTo(1);
        assertThat(registry.find(MicrometerWorkflowMetrics.PROCESS_DURATION).timer()).isNull();
    }

    @Test
    void outboxPickupLatencyKeepsTheDistributionThatSeparatesLocalFromCrossPodWakeups() {
        // The point of this timer is the shape, not the mean: rows a pod wrote are signalled and
        // land in milliseconds, rows written elsewhere wait for the poll. A mean averages the two
        // modes into a number that describes neither.
        metrics.outboxMessageRelayed(Duration.ofMillis(2));
        metrics.outboxMessageRelayed(Duration.ofMillis(248));

        var timer = registry.get(MicrometerWorkflowMetrics.OUTBOX_PICKUP_LATENCY).timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.max(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(248);
    }

    @Test
    void outboxBatchRecordsBothTheSizeAndTheTimeSpentInsideTheSends() {
        metrics.outboxBatchDelivered(100, Duration.ofMillis(300));

        assertThat(registry.get(MicrometerWorkflowMetrics.OUTBOX_BATCH_SIZE).summary().totalAmount()).isEqualTo(100);
        assertThat(registry.get(MicrometerWorkflowMetrics.OUTBOX_BATCH_DELIVER).timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(300);
    }

    @Test
    void anEmptyBatchStillRecordsItsSizeSoIdlePassesAreVisible() {
        // A relay waking to find nothing is the signal that a wakeup was spent for nothing, which
        // is what a poll-driven cross-pod pickup looks like from this side.
        metrics.outboxBatchDelivered(0, Duration.ZERO);

        assertThat(registry.get(MicrometerWorkflowMetrics.OUTBOX_BATCH_SIZE).summary().count()).isEqualTo(1);
    }

    @Test
    void relayCycleRecordsDrainingAndWaitingSeparatelySoDutyCycleIsComputable() {
        metrics.outboxRelayCycle(Duration.ofMillis(90), Duration.ofMillis(10));

        assertThat(registry.get(MicrometerWorkflowMetrics.OUTBOX_RELAY_DRAINING).timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(90);
        assertThat(registry.get(MicrometerWorkflowMetrics.OUTBOX_RELAY_WAITING).timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(10);
    }

    @Test
    void theOutboxMetersAreNoOpsWithoutAMetricsBackend() {
        // The engine has to run identically with no MeterRegistry, and these are on the hot path
        // of every transition in kafka mode.
        assertThatNoException().isThrownBy(() -> {
            WorkflowMetrics.NOOP.outboxMessageRelayed(Duration.ofMillis(1));
            WorkflowMetrics.NOOP.outboxBatchDelivered(10, Duration.ofMillis(1));
            WorkflowMetrics.NOOP.outboxRelayCycle(Duration.ofMillis(1), Duration.ofMillis(1));
        });
    }
}
