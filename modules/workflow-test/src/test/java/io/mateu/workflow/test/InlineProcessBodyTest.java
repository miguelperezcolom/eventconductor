package io.mateu.workflow.test;

import io.mateu.workflow.domain.aggregates.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** A PROCESS step whose child workflow is written in line: a dynamic fork when it runs under a forEach. */
@EventConductorTest
class InlineProcessBodyTest {

    @Test
    void eachRoomRunsTheInlineBodyInAChildProcessAndTheIdsAreCollected(WorkflowTestRuntime runtime) {
        runtime.whenTask("create-booking").thenAnswer(task ->
                TaskOutcome.complete(Map.of("roomBookingId", "B-" + task.variable("room"))));
        runtime.whenTask("confirm-booking").thenComplete();

        var reservation = runtime.start("room-bookings", "reservation-1",
                Map.of("rooms", List.of("101", "102", "103")));

        assertThat(reservation).isCompleted();
        assertThat(reservation.variable("roomBookings")).contains("B-101", "B-102", "B-103");

        // One child per room, of the definition the body becomes, each having run both steps.
        var children = childrenOf(runtime, reservation);
        assertThat(children).hasSize(3).allSatisfy(child -> {
            assertThat(child.status()).isEqualTo(ProcessStatus.COMPLETED);
            assertThat(child.state().getParentStepExecutionId()).isNotNull();
        });
        assertThat(runtime.invocationsOf("create"))
                .extracting(TaskInvocation::workflowDefinitionId).containsOnly("room-bookings.rooms");
        // The body is versioned with its parent.
        assertThat(children).extracting(child -> child.state().getWorkflowDefinitionVersion())
                .containsOnly(reservation.state().getWorkflowDefinitionVersion());
    }

    @Test
    void aRoomThatFailsCompensatesTheRoomsAlreadyBooked(WorkflowTestRuntime runtime) {
        runtime.whenTask("create-booking").thenAnswer(task -> "103".equals(task.variable("room"))
                ? TaskOutcome.fail("NO_AVAILABILITY", false)
                : TaskOutcome.complete(Map.of("roomBookingId", "B-" + task.variable("room"))));
        runtime.whenTask("confirm-booking").thenComplete();
        runtime.whenTask("cancel-booking").thenComplete();

        var reservation = runtime.start("room-bookings", "reservation-2",
                Map.of("rooms", List.of("101", "102", "103")));

        assertThat(reservation).isCompensated();
        assertThat(runtime.invocationsOf("cancelRoom")).extracting(cancel -> cancel.variable("roomBookingId"))
                .containsExactlyInAnyOrder("B-101", "B-102");
        assertThat(childrenOf(runtime, reservation))
                .extracting(child -> child.variable("room") + ":" + child.status())
                .containsExactlyInAnyOrder("101:COMPLETED", "102:COMPLETED", "103:ERROR");
    }

    /** The children this run's PROCESS step started (the runtime keeps every test's processes). */
    private static List<TestProcess> childrenOf(WorkflowTestRuntime runtime, TestProcess parent) {
        var parentSteps = parent.stepExecutions().stream()
                .map(io.mateu.workflow.domain.aggregates.StepExecution::getId).toList();
        return runtime.processes("room-bookings.rooms").stream()
                .filter(child -> parentSteps.contains(child.state().getParentStepExecutionId()))
                .toList();
    }

    @Test
    void anInlineBodyNestsInsideAnother(WorkflowTestRuntime runtime) {
        runtime.whenTask("deep-work").thenComplete(Map.of("result", "done"));

        var run = runtime.start("nested-inline", "nested-1", Map.of());

        assertThat(run).isCompleted().hasVariable("result", "done");
        assertThat(runtime.processes("nested-inline.outer")).hasSize(1);
        assertThat(runtime.processes("nested-inline.outer.inner")).hasSize(1);
    }

    @Test
    void anInlineBodyCannotBeStartedOnItsOwn(WorkflowTestRuntime runtime) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        runtime.start("room-bookings.rooms", "direct-1", Map.of("room", "101")))
                .hasStackTraceContaining("room-bookings.rooms");
        assertThat(runtime.processes("room-bookings.rooms")).isEmpty();
    }
}
