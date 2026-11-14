package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;

/**
 * whenTask(id) matches a step that names no task by its step id — a USER_TASK or an HTTP_CALL too, whose
 * task id (complete-form, http-call) is the engine's, not the step's.
 */
@EventConductorTest
class WhenTaskByStepIdTest {

    @Test
    void aUserTaskIsMatchedByItsStepId(WorkflowTestRuntime runtime) {
        runtime.whenTask("approve").thenComplete(Map.of("approved", true));
        runtime.whenTask("notify").thenComplete();

        var run = runtime.start("approval", "by-id-1", Map.of());

        assertThat(run).isCompleted().hasVariable("approved", true);
    }

    @Test
    void theBuiltInTaskIdStillMatchesEveryUserTask(WorkflowTestRuntime runtime) {
        runtime.whenTask("complete-form").thenComplete();
        runtime.whenTask("notify").thenComplete();

        assertThat(runtime.start("approval", "by-id-2", Map.of())).isCompleted();
    }

    @Test
    void aBuiltInCallIsMatchedByItsStepId(WorkflowTestRuntime runtime) {
        runtime.whenTask("charge").thenComplete(Map.of("chargeId", "by-task-mock"));

        var run = runtime.start("http-charge", "by-id-3", Map.of("bookingId", "B", "amount", 1));

        assertThat(run).isCompleted().hasVariable("chargeId", "by-task-mock");
    }
}
