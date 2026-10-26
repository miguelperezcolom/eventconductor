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
    void everyTestRunSharesOneDefinitionTagSoRunsOpenNoSeriesOfTheirOwn() {
        metrics.processStarted("orders");
        int before = registry.getMeters().size();
        for (int i = 0; i < 5; i++) {
            String run = io.mateu.workflow.domain.aggregates.TestRunIds.of(i % 2 == 0 ? "orders" : null,
                    java.util.UUID.randomUUID().toString());
            metrics.processStarted(run);
            metrics.processCompleted(run, Duration.ofMillis(5));
            metrics.stepExecutionFinished(run, StepExecutionStatus.COMPLETED, Duration.ofMillis(1));
            metrics.retryPerformed(run, WorkflowMetrics.RetryTrigger.MANUAL);
        }
        int afterFirstRun = registry.getMeters().size();
        metrics.processStarted(io.mateu.workflow.domain.aggregates.TestRunIds.of("orders", java.util.UUID.randomUUID().toString()));
        assertThat(registry.getMeters().size()).isEqualTo(afterFirstRun).isGreaterThan(before);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_STARTED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "vscode-test-run").counter().count()).isEqualTo(6);
        assertThat(registry.getMeters()).noneMatch(meter -> {
            String tag = meter.getId().getTag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID);
            return tag != null && tag.startsWith("vscode-") && !tag.equals("vscode-test-run");
        });
    }

    @Test
    void openIncidentsAreAGaugePerDefinitionThatDropsToZeroInsteadOfVanishing() {
        var testRun = io.mateu.workflow.domain.aggregates.TestRunIds.of("orders", java.util.UUID.randomUUID().toString());
        metrics.openIncidentsObserved(java.util.Map.of("orders", 3L, "refunds", 1L, testRun, 2L));
        assertThat(openIncidents("orders")).isEqualTo(3);
        assertThat(openIncidents("refunds")).isEqualTo(1);
        assertThat(openIncidents("vscode-test-run")).isEqualTo(2);

        metrics.openIncidentsObserved(java.util.Map.of("orders", 1L));
        assertThat(openIncidents("orders")).isEqualTo(1);
        assertThat(openIncidents("refunds")).as("resolved, still a series").isZero();
        assertThat(openIncidents("vscode-test-run")).isZero();
    }

    private double openIncidents(String definition) {
        return registry.get(MicrometerWorkflowMetrics.OPEN_INCIDENTS)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, definition).gauge().value();
    }

    @Test
    void aPublishedIdThatOnlyLooksLikeATestRunKeepsItsOwnTag() {
        metrics.processStarted("vscode-integration");
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_STARTED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "vscode-integration").counter().count()).isEqualTo(1);
    }

    @Test
    void manualOperationsAreCountedByOperationAndDefinition() {
        metrics.processOperation("orders", "set-variables");
        metrics.processOperation("orders", "set-variables");
        metrics.processOperation("orders", "retry-from-step");

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESS_OPERATIONS)
                .tag(MicrometerWorkflowMetrics.TAG_OPERATION, "set-variables")
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "orders")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESS_OPERATIONS)
                .tag(MicrometerWorkflowMetrics.TAG_OPERATION, "retry-from-step")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void taskEscalationsAreCountedByActionAndDefinition() {
        metrics.taskEscalated("expenses", "reassign");
        metrics.taskEscalated("expenses", "reassign");
        metrics.taskEscalated(null, "escalateTo");

        assertThat(registry.get("eventconductor.tasks.escalations").tag("action", "reassign")
                .tag("workflowDefinitionId", "expenses").counter().count()).isEqualTo(2);
        assertThat(registry.get("eventconductor.tasks.escalations").tag("action", "escalateTo")
                .counter().count()).isEqualTo(1);
        new io.mateu.workflow.application.out.WorkflowMetrics() {}.taskEscalated("x", "notify");
    }

    @Test
    void purgedProcessesAreCountedByDefinitionAndStatus() {
        metrics.processesPurged("orders", io.mateu.workflow.domain.aggregates.ProcessStatus.COMPLETED, 3);
        metrics.processesPurged("orders", io.mateu.workflow.domain.aggregates.ProcessStatus.COMPLETED, 2);
        metrics.processesPurged("orders", io.mateu.workflow.domain.aggregates.ProcessStatus.ERROR, 1);
        metrics.processesPurged(null, null, 0);

        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_PURGED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "orders")
                .tag(MicrometerWorkflowMetrics.TAG_STATUS, "COMPLETED")
                .counter().count()).isEqualTo(5);
        assertThat(registry.get(MicrometerWorkflowMetrics.PROCESSES_PURGED)
                .tag(MicrometerWorkflowMetrics.TAG_STATUS, "ERROR")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find(MicrometerWorkflowMetrics.PROCESSES_PURGED)
                .tag(MicrometerWorkflowMetrics.TAG_WORKFLOW_DEFINITION_ID, "unknown").counter()).isNull();
    }

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
