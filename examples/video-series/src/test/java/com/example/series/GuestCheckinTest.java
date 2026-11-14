package com.example.series;

import io.mateu.workflow.test.EventConductorTest;
import io.mateu.workflow.test.WorkflowTestRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Chapter 8 — one process's SEND_MESSAGE completes another's WAIT_FOR_MESSAGE, correlated by the room number. */
@EventConductorTest(properties = SeriesTests.NO_CRON)
class GuestCheckinTest {

    @Test
    void housekeepingsMessageHandsTheGuestTheKeysOfThatRoomOnly(WorkflowTestRuntime runtime) {
        runtime.whenStep("clean-room").thenComplete(Map.of("cleanedBy", "Lucía"));
        runtime.whenStep("hand-over-keys").thenAnswer(task ->
                io.mateu.workflow.test.TaskOutcome.complete(Map.of("keyCard", "KEY-" + task.variable("room"))));

        var bob = runtime.start("guest-checkin", "CHECKIN-T1", Map.of("guest", "Bob", "room", "204"));
        var eve = runtime.start("guest-checkin", "CHECKIN-T2", Map.of("guest", "Eve", "room", "305"));
        assertThat(bob).isWaitingAt("wait-room-ready");

        var cleaning = runtime.start("room-cleaning", "CLEAN-T204", Map.of("room", "204"));

        assertThat(cleaning).isCompleted().hasPassedSteps("announce-ready");
        assertThat(bob).isCompleted().hasVariable("keyCard", "KEY-204").hasVariable("cleanedBy", "Lucía");
        assertThat(eve).isWaitingAt("wait-room-ready");
        assertThat(runtime.invocationsOf("hand-over-keys")).hasSize(1);
    }
}
