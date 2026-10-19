package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.EventReceipt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Event-trigger startPolicy — debounce, throttle (queue and drop), singleton (skip and replace) — on the test clock. */
@EventConductorTest
class FlowControlTest {

    private static EventReceipt.Outcome outcome(io.mateu.workflow.application.services.InboundEventService.Result result) {
        return result.receipts().getFirst().outcome();
    }

    @Test
    void aDebounceStartsOnceWithTheLastEventsDataAfterTheQuietPeriod(WorkflowTestRuntime runtime) {
        runtime.whenTask("sync").thenComplete();

        assertThat(outcome(runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C1", "seq", 1))))
                .isEqualTo(EventReceipt.Outcome.DEBOUNCED);
        runtime.advanceTime(Duration.ofSeconds(10));
        runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C1", "seq", 2));
        runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C2", "seq", 1));
        runtime.advanceTime(Duration.ofSeconds(20));
        runtime.publishEvent("orders", "com.acme.order.updated", Map.of("customerId", "C1", "seq", 3));

        // C2's window (opened at 10 s) closed at 40 s; C1's was pushed to 60 s by its third event.
        runtime.advanceTime(Duration.ofSeconds(15));
        assertThat(runtime.processes("order-sync")).hasSize(1);
        assertThat(runtime.processes("order-sync").getFirst()).hasVariable("customerId", "C2");

        runtime.advanceTime(Duration.ofSeconds(15));
        var started = runtime.processes("order-sync");
        assertThat(started).hasSize(2);
        assertThat(started.getLast()).isCompleted().hasVariable("customerId", "C1").hasVariable("seq", "3");
        assertThat(started.getLast().businessKey()).isEqualTo("sync-C1-3");
    }

    @Test
    void aThrottleQueuesWhatIsOverItsLimitAndReleasesItWindowByWindowInOrder(WorkflowTestRuntime runtime) {
        runtime.whenTask("render").thenComplete();
        for (int n = 1; n <= 5; n++) {
            var result = runtime.publishEvent("reports", "com.acme.report.requested", Map.of("tenant", "T1", "n", n));
            assertThat(outcome(result)).isEqualTo(n <= 2 ? EventReceipt.Outcome.STARTED : EventReceipt.Outcome.THROTTLED);
        }
        // Another tenant has its own window.
        assertThat(outcome(runtime.publishEvent("reports", "com.acme.report.requested", Map.of("tenant", "T2", "n", 9))))
                .isEqualTo(EventReceipt.Outcome.STARTED);
        assertThat(runtime.processes("report-throttled")).hasSize(3);

        runtime.advanceTime(Duration.ofSeconds(59));
        assertThat(numbers(runtime)).containsExactlyInAnyOrder("1", "2", "9");
        runtime.advanceTime(Duration.ofSeconds(1));
        // FIFO: the next window takes the two that arrived first.
        assertThat(numbers(runtime)).containsExactlyInAnyOrder("1", "2", "9", "3", "4");
        runtime.advanceTime(Duration.ofMinutes(1));
        assertThat(numbers(runtime)).containsExactlyInAnyOrder("1", "2", "9", "3", "4", "5");
        assertThat(runtime.processes("report-throttled")).allSatisfy(p -> assertThat(p).isCompleted());
    }

    private static java.util.List<String> numbers(WorkflowTestRuntime runtime) {
        return runtime.processes("report-throttled").stream().map(p -> p.variable("n")).toList();
    }

    @Test
    void aThrottleThatDropsDiscardsWhatIsOverItsLimit(WorkflowTestRuntime runtime) {
        runtime.whenTask("page").thenComplete();
        for (int n = 1; n <= 4; n++) {
            var result = runtime.publishEvent("alerts", "com.acme.alert.raised", Map.of("n", n));
            assertThat(outcome(result)).isEqualTo(n <= 2 ? EventReceipt.Outcome.STARTED : EventReceipt.Outcome.DROPPED);
        }
        runtime.advanceTime(Duration.ofMinutes(5));
        assertThat(runtime.processes("alert-dropped")).hasSize(2);
        assertThat(outcome(runtime.publishEvent("alerts", "com.acme.alert.raised", Map.of("n", 5))))
                .isEqualTo(EventReceipt.Outcome.STARTED);
    }

    @Test
    void aSingletonThatSkipsStartsNothingWhileOneIsRunningForTheKey(WorkflowTestRuntime runtime) {
        assertThat(outcome(runtime.publishEvent("accounts", "com.acme.account.changed", Map.of("account", "A1", "n", 1))))
                .isEqualTo(EventReceipt.Outcome.STARTED);
        var first = runtime.process("refresh-A1-1");
        assertThat(first).isWaitingAt("settled");

        assertThat(outcome(runtime.publishEvent("accounts", "com.acme.account.changed", Map.of("account", "A1", "n", 2))))
                .isEqualTo(EventReceipt.Outcome.SKIPPED);
        assertThat(outcome(runtime.publishEvent("accounts", "com.acme.account.changed", Map.of("account", "A2", "n", 3))))
                .isEqualTo(EventReceipt.Outcome.STARTED);

        runtime.sendMessage("refresh-settled", "refresh-A1-1");
        assertThat(first).isCompleted();
        assertThat(outcome(runtime.publishEvent("accounts", "com.acme.account.changed", Map.of("account", "A1", "n", 4))))
                .isEqualTo(EventReceipt.Outcome.STARTED);
        assertThat(runtime.processes("refresh-singleton")).hasSize(3);
    }

    @Test
    void aSingletonThatReplacesCancelsTheRunningOne(WorkflowTestRuntime runtime) {
        runtime.publishEvent("catalog", "com.acme.catalog.changed", Map.of("shop", "S1", "n", 1));
        var first = runtime.process("rebuild-1");
        assertThat(first).isWaitingAt("built");

        assertThat(outcome(runtime.publishEvent("catalog", "com.acme.catalog.changed", Map.of("shop", "S1", "n", 2))))
                .isEqualTo(EventReceipt.Outcome.REPLACED);
        assertThat(first).isCancelled();
        assertThat(runtime.process("rebuild-2")).isWaitingAt("built");
    }
}
