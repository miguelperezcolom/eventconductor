package io.mateu.workflow.domain.aggregates;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A USER_TASK's assignment, rendered when it starts and dispatched with it, and its escalations: due
 * moments derived from its start and the count already fired, folded into the deadline the scan
 * finds it by, recorded once each.
 */
class StepExecutionEscalationTest {

    static final Instant T0 = Instant.parse("2026-10-19T08:00:00Z");

    @BeforeEach
    void fixClock() {
        EngineClock.set(Clock.fixed(T0, ZoneId.of("UTC")));
    }

    @AfterEach
    void resetClock() {
        EngineClock.reset();
    }

    static Step userTask(String extra) {
        return JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "approval",
                 "workflowDefinitionId": "expenses", "preconditionStepId": "start" %s}""".formatted(extra), Step.class);
    }

    static final String ESCALATIONS = """
            , "timeout": "P10D",
              "assignment": {"assignee": "${manager}", "priority": 80},
              "escalation": [
                {"after": "P2D", "action": "reassign", "to": {"candidateGroups": ["managers"]}},
                {"after": "PT36H", "action": "notify"},
                {"after": "P5D", "escalateTo": "review"}]""";

    private static Process process() {
        return Process.builder().id("p-1").businessKey("order-7")
                .variables(List.of(new Variable("manager", "bob"))).build();
    }

    private StepExecution started(Step step) {
        return StepExecution.create(step, "p-1", 0).start(process());
    }

    @Test
    void theAssignmentIsRenderedAndDispatchedWithTheTask() {
        var execution = started(userTask(ESCALATIONS));

        var assignment = execution.assignment();
        assertThat(assignment.assignee()).isEqualTo("bob");
        assertThat(assignment.priority()).isEqualTo(80);
        assertThat(assignment.businessKey()).isEqualTo("order-7");
        var events = execution.popEvents();
        var request = events.stream()
                .filter(TaskExecutionRequested.class::isInstance).map(TaskExecutionRequested.class::cast)
                .findFirst().orElseThrow();
        assertThat(request.variables()).anyMatch(v -> HumanTaskAssignment.VARIABLE.equals(v.name())
                && HumanTaskAssignment.fromJson(v.value()).assignee().equals("bob"));
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().contains("assigned to bob"));
    }

    @Test
    void anAssignmentThatCannotBeRenderedFailsTheStep() {
        var execution = started(userTask(", \"assignment\": {\"priority\": \"${'urgent'}\"}"));

        assertThat(execution.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(execution.popEvents()).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().contains("could not be rendered"));
    }

    @Test
    void theDeadlineIsTheNextEscalationWhileOneIsDueBeforeTheLimit() {
        var execution = started(userTask(ESCALATIONS));
        var start = execution.getStartedAt();

        // Listed out of order on purpose: they fire in the order of their `after`.
        assertThat(execution.nextEscalationAt()).isEqualTo(start.plusHours(36));
        assertThat(execution.getDeadlineAt()).isEqualTo(start.plusHours(36));
        assertThat(execution.currentDeadline()).as("the time limit is still the time limit").isEqualTo(start.plusDays(10));
        assertThat(execution.escalationsDue(start.plusDays(1))).isEmpty();
        assertThat(execution.escalationsDue(start.plusDays(2))).extracting(StepExecution.DueEscalation::level)
                .containsExactly(1, 2);
        assertThat(execution.escalationsDue(start.plusDays(6))).extracting(StepExecution.DueEscalation::level)
                .containsExactly(1, 2, 3);
    }

    @Test
    void anEscalationIsRecordedOnceAndMovesTheDeadlineOn() {
        var execution = started(userTask(ESCALATIONS));
        var start = execution.getStartedAt();
        var managers = new HumanTaskAssignment(null, List.of(), List.of("managers"), 80, null, null, "order-7");

        execution.escalated(1, "reassign", managers, null);
        execution.escalated(2, "notify", null, null);

        assertThat(execution.escalationsFired()).isEqualTo(2);
        assertThat(execution.assignment().candidateGroups()).containsExactly("managers");
        assertThat(execution.escalationsDue(start.plusDays(2))).isEmpty();
        assertThat(execution.nextEscalationAt()).isEqualTo(start.plusDays(5));
        assertThat(execution.getDeadlineAt()).isEqualTo(start.plusDays(5));
        assertThat(execution.popEvents()).filteredOn(HumanTaskEscalationRequested.class::isInstance)
                .extracting(e -> ((HumanTaskEscalationRequested) e).level()).containsExactly(1, 2);

        execution.escalated(3, "escalateTo", null, "review");

        assertThat(execution.nextEscalationAt()).isNull();
        assertThat(execution.getDeadlineAt()).isEqualTo(start.plusDays(10));
        // The route this execution now takes on TIMEOUT; the definition is not touched.
        assertThat(JsonSerializer.pojoFromJson(execution.getStepJson(), Step.class).onTimeoutStepId()).isEqualTo("review");
    }

    @Test
    void aTimeLimitBeforeTheNextEscalationIsTheDeadline() {
        var execution = started(userTask(", \"timeout\": \"P1D\", \"escalation\": [{\"after\": \"P2D\"}]"));

        assertThat(execution.getDeadlineAt()).isEqualTo(execution.getStartedAt().plusDays(1));
    }

    @Test
    void aStepThatIsNotALiveUserTaskHasNoEscalations() {
        var execution = started(userTask(ESCALATIONS));
        execution.updateStatus(StepExecutionStatus.COMPLETED);

        assertThat(execution.escalationsDue(LocalDateTime.MAX)).isEmpty();
        assertThat(StepExecution.create(userTask(ESCALATIONS), "p-1", 0).nextEscalationAt()).isNull();
        assertThat(StepExecution.create(userTask(ESCALATIONS), "p-1", 0).escalationsDue(LocalDateTime.MAX)).isEmpty();
        var action = JsonSerializer.pojoFromJson("""
                {"id": "a", "type": "ACTION", "name": "A", "topic": "t"}""", Step.class);
        var actionExecution = StepExecution.create(action, "p-1", 0).start(process());
        assertThat(actionExecution.nextEscalationAt()).isNull();
        assertThat(actionExecution.assignment()).isNull();
        assertThat(actionExecution.escalationsFired()).isZero();
        assertThat(actionExecution.withStepJson("{not json").nextEscalationAt()).isNull();
        assertThat(actionExecution.withVariables(null).escalationsFired()).isZero();
        assertThat(actionExecution.withVariables(null).assignment()).isNull();
        assertThat(actionExecution.withVariables(List.of(new Variable(StepExecution.ESCALATIONS_VARIABLE, "x")))
                .escalationsFired()).isZero();
    }

    @Test
    void escalationsAreListedInTheOrderTheyFire() {
        var step = userTask(ESCALATIONS);

        assertThat(step.escalations()).extracting(Escalation::after).containsExactly("PT36H", "P2D", "P5D");
        assertThat(step.escalationTargets()).containsExactly("review");
        assertThat(step.escalations().get(0).effectiveAction()).isEqualTo("notify");
        assertThat(userTask("").escalations()).isEmpty();
        assertThat(new Escalation("soon", "shout", null, null, null).afterDuration()).isZero();
        assertThat(new Escalation("PT1H", "shout", null, null, null).effectiveAction()).isEqualTo("notify");
    }
}
