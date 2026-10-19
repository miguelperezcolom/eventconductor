package io.mateu.workflow.infra.in.rest;

import io.mateu.uidl.data.FieldDataType;
import io.mateu.uidl.data.FieldStereotype;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.application.out.FormsMetrics;
import io.mateu.workflow.application.services.HumanTaskEvents;
import io.mateu.workflow.application.services.TaskAuthorization;
import io.mateu.workflow.application.services.TaskCallers;
import io.mateu.workflow.application.services.TaskInbox;
import io.mateu.workflow.application.services.TaskInboxOrder;
import io.mateu.workflow.application.services.TaskTimeline;
import io.mateu.workflow.application.usecases.claimtask.ClaimTaskUseCase;
import io.mateu.workflow.application.usecases.completetask.CompleteTaskUseCase;
import io.mateu.workflow.application.usecases.reassigntask.ReassignTaskUseCase;
import io.mateu.workflow.application.usecases.releasetask.ReleaseTaskUseCase;
import io.mateu.workflow.domain.Field;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.FormExecutionStatus;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.Value;
import io.mateu.workflow.domain.Variable;
import io.mateu.workflow.infra.out.memory.InMemoryFormExecutionRepository;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /forms/api/tasks}: the inbox over HTTP, always as the caller. */
class TaskInboxControllerTest {

    InMemoryFormExecutionRepository repository;
    TaskCallers callers;
    TaskAuthorization taskAuthorization;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFormExecutionRepository();
        var forms = mock(FormRepository.class);
        when(forms.findById("approve")).thenReturn(Optional.of(new Form("approve", "Approve", "",
                List.of(new Field("decision", "Decision", FieldDataType.string, FieldStereotype.regular, true, "")))));
        taskAuthorization = mock(TaskAuthorization.class);
        var events = mock(HumanTaskEvents.class);
        var streamBridge = mock(StreamBridge.class);
        when(streamBridge.send(anyString(), any())).thenReturn(true);
        var timeline = new TaskTimeline(streamBridge);
        var inbox = new TaskInbox(repository, forms, taskAuthorization);
        callers = mock(TaskCallers.class);
        caller("carol", "finance");
        var controller = new TaskInboxController(inbox, callers,
                new ClaimTaskUseCase(repository, forms, inbox, taskAuthorization, events, timeline),
                new ReleaseTaskUseCase(repository, inbox, events, timeline),
                new ReassignTaskUseCase(repository, inbox, events, timeline),
                new CompleteTaskUseCase(repository, forms, streamBridge, mock(FormsMetrics.class), taskAuthorization,
                        events, inbox));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();

        var now = TaskInboxOrder.nowUtc();
        repository.save(task("t-group").candidateGroups(List.of("finance")).priority(80)
                .dueDate(now.minusHours(1)).businessKey("order-1").restricted(true).build());
        repository.save(task("t-open").priority(40).dueDate(now.plusDays(1)).build());
        repository.save(task("t-alice").userId("alice").status(FormExecutionStatus.ASSIGNED).restricted(true).build());
    }

    private void caller(String subject, String... groups) {
        when(callers.current()).thenReturn(TaskCaller.of(subject, List.of(groups), "admin".equals(subject)));
    }

    private static FormExecution.FormExecutionBuilder task(String id) {
        return FormExecution.builder().id(id).formId("approve").processId("p-" + id).stepId("approve")
                .stepExecutionId("se-" + id).status(FormExecutionStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 10, 1, 9, 0))
                .variables(List.of(new Variable("amount", "100"))).values(List.of());
    }

    @Test
    void theInboxListsWhatTheCallerMaySeeMostUrgentFirst() throws Exception {
        mvc.perform(get("/forms/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].id").value("t-group"))
                .andExpect(jsonPath("$.items[0].overdue").value(true))
                .andExpect(jsonPath("$.items[0].priority").value(80))
                .andExpect(jsonPath("$.items[0].candidateGroups[0]").value("finance"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-01T09:00:00Z"))
                .andExpect(jsonPath("$.items[0].variables").doesNotExist())
                .andExpect(jsonPath("$.items[1].id").value("t-open"));
        mvc.perform(get("/forms/api/tasks").param("scope", "groups").param("overdue", "true")
                        .param("businessKey", "order-1").param("minPriority", "50").param("sort", "due")
                        .param("status", "pending, assigned").param("dueBefore", "2999-01-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/forms/api/tasks").param("dueBefore", "2000-01-01T00:00:00+02:00"))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/forms/api/tasks").param("dueBefore", "2999-01-01T00:00:00"))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void badParametersAreA400() throws Exception {
        mvc.perform(get("/forms/api/tasks").param("scope", "everyone")).andExpect(status().isBadRequest());
        mvc.perform(get("/forms/api/tasks").param("status", "DONE")).andExpect(status().isBadRequest());
        mvc.perform(get("/forms/api/tasks").param("dueBefore", "soon")).andExpect(status().isBadRequest());
    }

    @Test
    void anonymousIsRefused() throws Exception {
        when(callers.current()).thenReturn(TaskCaller.anonymous());

        mvc.perform(get("/forms/api/tasks")).andExpect(status().isUnauthorized());
    }

    @Test
    void aTaskIsReadClaimedCompletedAndAnsweredAsTheCaller() throws Exception {
        mvc.perform(get("/forms/api/tasks/t-group"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variables.amount").value("100"))
                .andExpect(jsonPath("$.values").isMap());
        mvc.perform(get("/forms/api/tasks/t-alice")).andExpect(status().isNotFound());

        mvc.perform(post("/forms/api/tasks/t-group/claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("carol"))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
        caller("erin", "finance");
        mvc.perform(post("/forms/api/tasks/t-group/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"decision\":\"OK\"}}"))
                .andExpect(status().isNotFound());
        caller("carol", "finance");
        // A required field missing is the submitter's mistake: 400.
        mvc.perform(post("/forms/api/tasks/t-group/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"other\":\"x\"}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/forms/api/tasks/t-group/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"decision\":\"OK\",\"note\":null}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(repository.findById("t-group").orElseThrow().values()).containsExactly(new Value("decision", "OK"));
        mvc.perform(post("/forms/api/tasks/t-group/release")).andExpect(status().isConflict());
    }

    @Test
    void completingWithoutAnswersOrHoldingTheTaskIsRefused() throws Exception {
        mvc.perform(post("/forms/api/tasks/t-open/complete")).andExpect(status().isConflict());
    }

    @Test
    void theHolderReleasesAndAnAdminAssigns() throws Exception {
        mvc.perform(post("/forms/api/tasks/t-open/claim")).andExpect(status().isOk());
        mvc.perform(post("/forms/api/tasks/t-open/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post("/forms/api/tasks/t-open/assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"bob\"}"))
                .andExpect(status().isForbidden());
        caller("admin");
        mvc.perform(post("/forms/api/tasks/t-open/assign").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"bob\",\"candidateGroups\":[\"finance\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("bob"))
                .andExpect(jsonPath("$.candidateGroups[0]").value("finance"));
    }

    @Test
    void aFormTheCallerMayNotWorkOnIsA403() throws Exception {
        doThrow(new FlowAuthorizationDeniedException("missing role finance")).when(taskAuthorization)
                .refuseIfCallerMayNot(eq("claim"), any(), eq("t-open"));

        mvc.perform(post("/forms/api/tasks/t-open/claim")).andExpect(status().isForbidden());
    }

    @Test
    void aTaskViewCarriesItsMomentsAsInstants() {
        var due = LocalDateTime.of(2026, 10, 20, 9, 0);
        var view = TaskInboxController.view(task("t").dueDate(due).followUpDate(due).escalatedAt(due)
                .values(null).variables(null).build(), due.plusHours(1), true);

        assertThat(view.dueDate()).isEqualTo(due.toInstant(ZoneOffset.UTC).toString());
        assertThat(view.overdue()).isTrue();
        assertThat(view.variables()).isEmpty();
        assertThat(view.values()).isEmpty();
        assertThat(TaskInboxController.moment(" ")).isNull();
        assertThat(TaskInboxController.statuses(" ")).isEmpty();
    }
}
