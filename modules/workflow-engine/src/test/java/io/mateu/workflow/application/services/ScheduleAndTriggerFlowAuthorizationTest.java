package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.EventTriggerService.TriggerDraft;
import io.mateu.workflow.application.services.ScheduleService.ScheduleDraft;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.config.EventDestinationsProperties;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemoryEventReceiptRepository;
import io.mateu.workflow.infra.out.memory.InMemoryEventTriggerRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.infra.out.memory.InMemoryTriggerPauseRepository;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A schedule or an event trigger starts its workflow as SYSTEM, which flow authorization does not
 * judge — so with flow authorization on, whoever creates, edits or runs one is judged instead, against
 * the workflow it starts, exactly as if they had started it themselves.
 */
class ScheduleAndTriggerFlowAuthorizationTest {

    static final AuthorizationContext HOLDER = new AuthorizationContext("ana", List.of("payments:write"), List.of());
    static final AuthorizationContext OUTSIDER = new AuthorizationContext("bob", List.of(), List.of());

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final InMemoryCronScheduleRepository schedules = new InMemoryCronScheduleRepository();
    final InMemoryEventTriggerRepository triggers = new InMemoryEventTriggerRepository();
    final List<ProcessCreationRequested> started = new ArrayList<>();

    final ScheduleService scheduleService = new ScheduleService(definitions, schedules,
            new InMemorySchedulePauseRepository(), started::add).withFlowAuthorization(true);
    final EventTriggerService triggerService = new EventTriggerService(definitions, new InMemoryTriggerPauseRepository(),
            new InMemoryEventReceiptRepository(), new EventDestinationsProperties(), triggers).withFlowAuthorization(true);

    static WorkflowDefinition restricted(String id) {
        return new WorkflowDefinition(id, "The " + id, 1, "", false, 0, false, null, 0, List.of(), false,
                WorkflowStatus.ACTIVE, WorkflowStatus.ACTIVE, List.of("payments:write"), List.of(), 0, null);
    }

    static WorkflowDefinition open(String id) {
        return new WorkflowDefinition(id, "The " + id, 1, null, false, 0, false, null, 0, List.of());
    }

    @BeforeEach
    void someWorkflows() {
        var all = List.of(
                restricted("payout").withSchedules(List.of(new CronSchedule("nightly-payout", "0 0 1 * * *", null, null))),
                open("report"));
        when(definitions.findAll()).thenReturn(all);
        when(definitions.findById(anyString())).thenReturn(Optional.empty());
        all.forEach(d -> when(definitions.findById(d.id())).thenReturn(Optional.of(d)));
    }

    @Test
    void aCallerWhoMayNotStartTheWorkflowCannotScheduleItAndNothingIsStored() {
        assertThatThrownBy(() -> scheduleService.create(new ScheduleDraft("p", "payout", "0 0 9 * * *", null, null), OUTSIDER))
                .isInstanceOf(FlowAuthorizationDeniedException.class)
                .hasMessageContaining("bob").hasMessageContaining("payments:write");
        assertThatThrownBy(() -> scheduleService.create(new ScheduleDraft("p", "payout", "0 0 9 * * *", null, null), null))
                .as("an unidentified caller holds nothing").isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(schedules.findAll()).isEmpty();

        assertThat(scheduleService.create(new ScheduleDraft("p", "payout", "0 0 9 * * *", null, null), HOLDER).id())
                .isEqualTo("p");
    }

    @Test
    void aScheduleCannotBeRepointedAtOrEditedOnARestrictedWorkflowByAnOutsider() {
        scheduleService.create(new ScheduleDraft("r", "report", "0 0 9 * * *", null, null), OUTSIDER);
        assertThatThrownBy(() -> scheduleService.update("r", new ScheduleDraft("r", "payout", "0 0 9 * * *", null, null), OUTSIDER))
                .isInstanceOf(FlowAuthorizationDeniedException.class);

        scheduleService.create(new ScheduleDraft("p", "payout", "0 0 9 * * *", null, null), HOLDER);
        assertThatThrownBy(() -> scheduleService.update("p", new ScheduleDraft("p", "report", "0 0 9 * * *", null, null), OUTSIDER))
                .as("taking over a restricted workflow's schedule").isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(scheduleService.get("p").workflowId()).isEqualTo("payout");
    }

    @Test
    void runNowIsJudgedAgainstTheCallerButStillStartsAsSystem() {
        assertThatThrownBy(() -> scheduleService.runNow("nightly-payout", "bob", OUTSIDER))
                .isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(started).isEmpty();

        scheduleService.runNow("nightly-payout", "ana", HOLDER);
        assertThat(started).singleElement().satisfies(start ->
                assertThat(AuthorizationContext.isSystem(start.caller())).isTrue());
    }

    @Test
    void aCallerWhoMayNotStartTheWorkflowCannotCreateOrEditATriggerForIt() {
        assertThatThrownBy(() -> triggerService.create(new TriggerDraft("t", "payout", "payments", "*", null, null, null), OUTSIDER))
                .isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(triggers.findAll()).isEmpty();

        triggerService.create(new TriggerDraft("t", "payout", "payments", "*", null, null, null), HOLDER);
        assertThatThrownBy(() -> triggerService.update("payout", "t",
                new TriggerDraft("t", "payout", "payments", "com.acme.*", null, null, Map.of()), OUTSIDER))
                .isInstanceOf(FlowAuthorizationDeniedException.class);
        assertThat(triggerService.get("payout", "t").type()).isEqualTo("*");

        assertThat(triggerService.create(new TriggerDraft("o", "report", "reports", "*", null, null, null), OUTSIDER).triggerId())
                .as("an open workflow is open to anyone").isEqualTo("o");
    }

    @Test
    void offIsOff() {
        var unchecked = new ScheduleService(definitions, schedules, new InMemorySchedulePauseRepository(), started::add);
        assertThat(unchecked.create(new ScheduleDraft("p", "payout", "0 0 9 * * *", null, null), OUTSIDER).id()).isEqualTo("p");
    }
}
