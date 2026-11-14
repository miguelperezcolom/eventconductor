package io.mateu.workflow.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.mateu.workflow.test.WorkflowAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Whether a USER_TASK's escalation levels fired, as time passed. */
@EventConductorTest
class EscalationAssertionsTest {

    @Test
    void theLevelsFireAsTimePasses(WorkflowTestRuntime runtime) {
        var expense = runtime.start("expense-approval", "esc-1",
                Map.of("manager", "bob", "region", "emea", "amount", 200, "expenseId", "E-1"));
        assertThat(expense).isWaitingAt("approve").hasNotEscalated("approve");

        runtime.advanceTime(Duration.ofHours(48));
        assertThat(expense).isWaitingAt("approve").hasEscalated("approve").hasEscalated("approve", 1);

        runtime.advanceTime(Duration.ofDays(2));
        assertThat(expense).hasEscalated("approve", 2);
        assertThatThrownBy(() -> assertThat(expense).hasEscalated("approve", 3))
                .hasMessageContaining("to have escalated 3 time(s), but it escalated 2")
                .hasMessageContaining("1. after PT48H    reassign   fired")
                .hasMessageContaining("3. after P5D      escalateTo not fired");
    }

    @Test
    void aTaskThatHasNotEscalatedSaysWhen(WorkflowTestRuntime runtime) {
        var expense = runtime.start("expense-approval", "esc-2",
                Map.of("manager", "bob", "region", "emea", "amount", 200, "expenseId", "E-2"));

        assertThatThrownBy(() -> assertThat(expense).hasEscalated("approve"))
                .hasMessageContaining("no escalation fired")
                .hasMessageContaining("2. after P4D      notify     not fired");
        assertThatThrownBy(() -> assertThat(expense).hasEscalated("start"))
                .hasMessageContaining("declares no escalation");
        assertThatThrownBy(() -> assertThat(expense).hasEscalated("nope"))
                .hasMessageContaining("has not started");
    }
}
