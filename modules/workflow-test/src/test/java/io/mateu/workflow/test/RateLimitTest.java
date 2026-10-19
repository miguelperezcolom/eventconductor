package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** workflow.workers.rate-limits: tasks over a topic's limit wait PENDING, by priority, fairly across processes. */
@EventConductorTest(properties = {
        "workflow.workers.rate-limits.partner-api.permits=2",
        "workflow.workers.rate-limits.partner-api.period=PT1M"})
class RateLimitTest {

    /** The topic's window is engine state shared by the tests of one context: each starts in a fresh one. */
    @org.junit.jupiter.api.BeforeEach
    void freshWindow(WorkflowTestRuntime runtime) {
        runtime.whenTask("partner-api").thenComplete();
        runtime.advanceTime(Duration.ofMinutes(5));
    }

    @Test
    void tasksOverTheLimitWaitPendingWithoutTheirTimeoutRunning(WorkflowTestRuntime runtime) {
        runtime.whenTask("partner-api").thenComplete();
        var calls = new java.util.ArrayList<TestProcess>();
        for (int i = 0; i < 5; i++) {
            calls.add(runtime.start("partner-call", "rl-" + i, Map.of()));
        }
        assertThat(runtime.invocationsOf("call")).hasSize(2);
        assertThat(calls.get(4)).isWaitingAt("call").hasStepStatus("call", StepExecutionStatus.PENDING);

        runtime.advanceTime(Duration.ofMinutes(1));
        assertThat(runtime.invocationsOf("call")).hasSize(4);
        runtime.advanceTime(Duration.ofMinutes(1));
        assertThat(runtime.invocationsOf("call")).hasSize(5);
        // Two minutes waiting, a 30 s timeout: it never ran out, because it only starts at dispatch.
        assertThat(calls).allSatisfy(call -> assertThat(call).isCompleted());
        var at = runtime.invocationsOf("call").stream().map(TaskInvocation::at).toList();
        assertThat(Duration.between(at.get(0), at.get(4))).isGreaterThanOrEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void higherPriorityTasksLeaveTheQueueFirst(WorkflowTestRuntime runtime) {
        runtime.whenTask("partner-api").thenComplete();
        for (int i = 0; i < 3; i++) {
            runtime.start("partner-call", "normal-" + i, Map.of());
        }
        runtime.start("partner-urgent", "urgent-1", Map.of());
        runtime.start("partner-urgent", "urgent-2", Map.of());
        assertThat(runtime.invocationsOf("call")).hasSize(2);
        assertThat(runtime.invocationsOf("urgent-call")).isEmpty();

        runtime.advanceTime(Duration.ofMinutes(1));
        // The urgent ones (priority 90) overtook the normal one that had been waiting longer.
        assertThat(runtime.invocationsOf("urgent-call")).hasSize(2);
        assertThat(runtime.invocationsOf("call")).hasSize(2);
        runtime.advanceTime(Duration.ofMinutes(1));
        assertThat(runtime.invocationsOf("call")).hasSize(3);
    }

    @Test
    void oneProcessWithManyTasksDoesNotStarveTheOthers(WorkflowTestRuntime runtime) {
        runtime.whenTask("partner-api").thenComplete();
        var batch = runtime.start("partner-batch", "batch-1", Map.of("items", java.util.List.of(1, 2, 3, 4, 5, 6)));
        assertThat(runtime.invocationsOf("batch-call")).hasSize(2);
        var single = runtime.start("partner-call", "single-1", Map.of());
        assertThat(runtime.invocationsOf("call")).isEmpty();

        runtime.advanceTime(Duration.ofMinutes(1));
        // Round-robin across processes: the single call goes out in the very next window, beside one batch item.
        assertThat(runtime.invocationsOf("call")).hasSize(1);
        assertThat(runtime.invocationsOf("batch-call")).hasSize(3);
        assertThat(single).isCompleted();

        runtime.advanceTime(Duration.ofMinutes(2));
        assertThat(runtime.invocationsOf("batch-call")).hasSize(6);
        assertThat(batch).isCompleted();
    }
}
