package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** A process left over from an earlier test never consumes the current test's mocks. */
@EventConductorTest
class IsolationTest {

    @Test
    void leftoversAreParkedNotAnswered(WorkflowTestRuntime runtime) {
        var leftover = runtime.start("flaky-charge", "iso-1", Map.of());   // un-mocked: parked
        assertThat(leftover).isWaitingAt("charge");

        runtime.endTest();
        runtime.beginTest();   // what the extension does between two tests

        runtime.whenTask("charge-card").thenComplete();
        var fresh = runtime.start("flaky-charge", "iso-2", Map.of());
        assertThat(fresh).isCompleted();
        assertThat(runtime.invocationsOf("charge")).extracting(TaskInvocation::processId).containsOnly(fresh.id());
        assertThat(leftover).isWaitingAt("charge");
    }
}
