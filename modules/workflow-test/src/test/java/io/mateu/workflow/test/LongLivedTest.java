package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** CONTINUE_AS_NEW on the test clock: a subscription runs month after month, its history bounded by retention. */
@EventConductorTest
class LongLivedTest {

    @Test
    void aSubscriptionContinuesAsNewEachPeriodAndRetentionForgetsTheOldRuns(WorkflowTestRuntime runtime) {
        runtime.whenTask("billing").thenAnswer(invocation -> TaskOutcome.complete(Map.of("period",
                String.valueOf(Integer.parseInt(invocation.variable("period")) + 1))));

        var first = runtime.start("subscription", "sub-C1", Map.of("customerId", "C1", "period", 0));
        assertThat(first).isWaitingAt("period").hasVariable("period", "1");

        // 30-day timers, read on the wall clock: a day of slack covers a DST change on the way.
        runtime.advanceTime(Duration.ofDays(31));
        var second = runtime.process("sub-C1");
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second).isWaitingAt("period").hasVariable("period", "2").hasVariable("customerId", "C1");
        assertThat(second.state().getContinuedFromProcessId()).isEqualTo(first.id());
        assertThat(first.status()).isEqualTo(ProcessStatus.COMPLETED);
        assertThat(first.state().getContinuedAsProcessId()).isEqualTo(second.id());
        assertThat(first.state().outcome()).isEqualTo("continued");

        runtime.advanceTime(Duration.ofDays(93));
        var current = runtime.process("sub-C1");
        assertThat(current).isWaitingAt("period").hasVariable("period", "5");
        // Every run but the current one ended over a day ago: retention (P1D) purged them, so the history of
        // a subscription that has been running for four months is one run long.
        assertThat(runtime.processes("subscription").stream().filter(p -> "sub-C1".equals(p.businessKey())))
                .singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(current.id()));
        assertThat(current.state().getContinuedFromProcessId()).isNotNull();
    }
}
