package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** A test's own properties win over the kit's defaults and the engine's: here, retries without jitter. */
@EventConductorTest(properties = {"workflow.retry.backoff-jitter=0.0", "workflow.cron-enabled=false"})
class PropertiesTest {

    @Test
    void retryMomentsAreExactWithoutJitter(WorkflowTestRuntime runtime) {
        runtime.whenTask("charge-card").thenFail("DOWN").thenFail("DOWN").thenComplete();

        var charge = runtime.start("flaky-charge", "props-1", Map.of());
        runtime.advanceTime(Duration.ofMinutes(1));

        assertThat(charge).isCompleted();
        var calls = runtime.invocationsOf("charge");
        assertThat(Duration.between(calls.get(0).at(), calls.get(1).at())).isEqualTo(Duration.ofSeconds(1));
        assertThat(Duration.between(calls.get(1).at(), calls.get(2).at())).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void cronStartsCanBeSwitchedOff(WorkflowTestRuntime runtime) {
        var before = runtime.processes("nightly-report").size();
        runtime.advanceTime(Duration.ofDays(2));
        assertThat(runtime.processes("nightly-report")).hasSize(before);
    }
}
