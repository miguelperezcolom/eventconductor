package io.mateu.workflow.application.usecases.checktimeout.escalateusertask;

import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.ProcessLockService;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.dtos.events.integration.ExternalEventRequested;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import io.mateu.workflow.dtos.humantasks.HumanTaskEscalationRequested;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The escalations that came due, fired once each, recorded, announced, and routed when they say so. */
class EscalateUserTaskHandlerTest {

    static final Instant T0 = Instant.parse("2026-10-19T08:00:00Z");

    StepExecutionRepository steps = mock(StepExecutionRepository.class);
    ProcessRepository processes = mock(ProcessRepository.class);
    ProcessLockService lock = mock(ProcessLockService.class);
    WorkflowMetrics metrics = mock(WorkflowMetrics.class);
    EscalateUserTaskHandler handler;
    StepExecution execution;

    @BeforeEach
    void setUp() {
        EngineClock.set(Clock.fixed(T0, ZoneId.of("UTC")));
        when(lock.runExclusively(anyString(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        when(processes.findById("p-1")).thenReturn(Optional.of(Process.builder().id("p-1").businessKey("order-7")
                .workflowDefinitionId("expenses").variables(List.of(new Variable("boss", "zoe"))).build()));
        handler = new EscalateUserTaskHandler(steps, processes, lock, metrics);
    }

    @AfterEach
    void resetClock() {
        EngineClock.reset();
    }

    private void taskWith(String escalation) {
        var step = JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "approval",
                 "workflowDefinitionId": "expenses",
                 "assignment": {"assignee": "alice", "priority": 70},
                 "escalation": %s}""".formatted(escalation), Step.class);
        execution = StepExecution.create(step, "p-1", 0).start(processes.findById("p-1").orElseThrow());
        execution.popEvents();
        when(steps.findById(execution.id())).thenReturn(Optional.of(execution));
    }

    private void at(String duration) {
        EngineClock.set(Clock.fixed(T0.plus(java.time.Duration.parse(duration)), ZoneId.of("UTC")));
    }

    @Test
    void aReassignmentReplacesWhoTheTaskIsForAndKeepsItsUrgency() {
        taskWith("""
                [{"after": "PT1H", "action": "reassign", "to": {"assignee": "${boss}", "candidateGroups": ["managers"]}},
                 {"after": "PT2H"}]""");
        at("PT1H");

        handler.handle(execution.id());

        var events = execution.popEvents();
        var escalation = events.stream().filter(HumanTaskEscalationRequested.class::isInstance)
                .map(HumanTaskEscalationRequested.class::cast).findFirst().orElseThrow();
        assertThat(escalation.level()).isEqualTo(1);
        assertThat(escalation.action()).isEqualTo("reassign");
        assertThat(escalation.reassignTo().assignee()).isEqualTo("zoe");
        assertThat(escalation.reassignTo().candidateGroups()).containsExactly("managers");
        assertThat(escalation.reassignTo().priority()).isEqualTo(70);
        assertThat(execution.assignment().assignee()).isEqualTo("zoe");
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().equals("Escalation 1 of 2 (after PT1H): task reassigned to zoe, candidate groups [managers]."));
        assertThat(execution.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        verify(metrics).taskEscalated("expenses", "reassign");
        verify(steps).save(execution);

        // Redelivered at the same moment: nothing left due.
        handler.handle(execution.id());
        assertThat(execution.popEvents()).noneMatch(HumanTaskEscalationRequested.class::isInstance);
    }

    @Test
    void everyEscalationDueFiresInOrderAndAnEscalateToEndsTheTaskAndRoutesTheFlow() {
        taskWith("""
                [{"after": "PT1H", "action": "notify"},
                 {"after": "PT2H", "escalateTo": "review"}]""");
        at("PT3H");

        handler.handle(execution.id());

        var levels = execution.popEvents().stream().filter(HumanTaskEscalationRequested.class::isInstance)
                .map(HumanTaskEscalationRequested.class::cast).toList();
        assertThat(levels).extracting(HumanTaskEscalationRequested::level).containsExactly(1, 2);
        assertThat(levels.get(1).escalateTo()).isEqualTo("review");
        assertThat(execution.getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(JsonSerializer.pojoFromJson(execution.getStepJson(), Step.class).onTimeoutStepId()).isEqualTo("review");
        verify(metrics).stepExecutionFinished(eq("expenses"), eq(StepExecutionStatus.TIMEOUT), any());
    }

    @Test
    void anEscalationsEventIsPublishedWithItAndOneThatCannotBeIsOnlyLogged() {
        taskWith("""
                [{"after": "PT1H", "publishEvent": {"destination": "notifications", "type": "com.acme.task.escalated",
                   "payload": {"task": "${step.id}", "level": "${escalation.level}", "assignee": "${escalation.assignee}"}}},
                 {"after": "PT2H", "publishEvent": {"destination": "notifications", "type": "t", "payload": {"x": "${1/0}"}}}]""");
        at("PT2H");

        handler.handle(execution.id());

        var events = execution.popEvents();
        var published = events.stream().filter(ExternalEventRequested.class::isInstance)
                .map(ExternalEventRequested.class::cast).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).eventId()).isEqualTo(execution.id() + "-escalation-1");
        assertThat(published.get(0).data()).contains("\"task\":\"approve\"").contains("\"level\":1")
                .contains("\"assignee\":\"alice\"");
        assertThat(events).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().startsWith("Escalation 2 of 2 (after PT2H): its event could not be published"));
        assertThat(execution.escalationsFired()).isEqualTo(2);
    }

    @Test
    void aToThatCannotBeRenderedLeavesTheTaskAsItWasButStillFires() {
        taskWith("""
                [{"after": "PT1H", "to": {"assignee": "${1/0}"}}]""");
        at("PT1H");

        handler.handle(execution.id());

        assertThat(execution.assignment().assignee()).isEqualTo("alice");
        assertThat(execution.escalationsFired()).isEqualTo(1);
        assertThat(execution.popEvents()).anyMatch(e -> e instanceof TaskLogEmitted log
                && log.message().contains("could not be rendered"));
    }

    @Test
    void nothingDueOrNoProcessOrNoLockIsANoOp() {
        taskWith("[{\"after\": \"PT1H\"}]");

        handler.handle(execution.id());
        at("PT1H");
        when(processes.findById("p-1")).thenReturn(Optional.empty());
        handler.handle(execution.id());
        org.mockito.Mockito.doReturn(false).when(lock).runExclusively(anyString(), any());
        handler.handle(execution.id());

        assertThat(execution.escalationsFired()).isZero();
        verify(steps, never()).save(any());
    }

    @Test
    void anIterationsEscalationSeesItsItem() {
        var step = JsonSerializer.pojoFromJson("""
                {"id": "approve", "type": "USER_TASK", "name": "Approve", "formId": "approval",
                 "workflowDefinitionId": "expenses",
                 "forEach": {"items": "lines", "as": "line"},
                 "escalation": [{"after": "PT1H", "to": {"assignee": "${line}-lead"}}]}""", Step.class);
        var container = StepExecution.create(step, "p-1", 0);
        var iteration = StepExecution.iterationOf(container, container.getStepJson(), 0, Map.of("line", "a", "index", "0"));
        iteration.start(processes.findById("p-1").orElseThrow());
        when(steps.findById(iteration.id())).thenReturn(Optional.of(iteration));
        at("PT1H");

        handler.handle(iteration.id());

        assertThat(iteration.assignment().assignee()).isEqualTo("a-lead");
    }
}
