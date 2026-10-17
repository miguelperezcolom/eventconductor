package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.ScheduleService;
import io.mateu.workflow.domain.aggregates.CronSchedule;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.ProcessCreationRequested;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryCronScheduleRepository;
import io.mateu.workflow.infra.out.memory.InMemorySchedulePauseRepository;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The schedules endpoints through the dispatcher, over the real service and in-memory stores. */
class ScheduleRestControllerMvcTest {

    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final List<ProcessCreationRequested> started = new ArrayList<>();
    final MessageApiProperties apiProperties = new MessageApiProperties();

    {
        // These tests call without a key: an isolated engine that opted into anonymous access (off by default since 2.27.0).
        apiProperties.setAllowAnonymous(true);
    }
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(definitions.findAll()).thenReturn(List.of(
                new WorkflowDefinition("report", "Report", 1, null, false, 0, false, null, 0, List.of())
                        .withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null)))));
        var service = new ScheduleService(definitions, new InMemoryCronScheduleRepository(),
                new InMemorySchedulePauseRepository(), started::add)
                .withClock(() -> Instant.parse("2026-10-14T10:00:00Z"));
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) () -> new AuthorizationContext("alice", List.of(), List.of()));
        var controller = new ScheduleRestController(service, apiProperties, beans.getBeanProvider(CallerResolver.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static final String NIGHTLY = """
            {"id": "nightly", "workflowId": "report", "cron": "0 0 9 * * *", "zone": "UTC", "variables": {"region": "EMEA"}}
            """;

    @Test
    void createsListsEditsAndDeletesASchedule() throws Exception {
        mvc.perform(post("/workflow/api/schedules").contentType("application/json").content(NIGHTLY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/workflow/api/schedules/nightly"))
                .andExpect(jsonPath("$.sourceLabel").value("UI/API"))
                .andExpect(jsonPath("$.editable").value(true))
                .andExpect(jsonPath("$.nextFires[0]").value("2026-10-15T09:00:00Z[UTC]"));

        mvc.perform(get("/workflow/api/schedules").param("workflowId", "report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", contains("eom", "nightly")));

        mvc.perform(put("/workflow/api/schedules/nightly").contentType("application/json")
                        .content("{\"workflowId\": \"report\", \"cron\": \"@hourly\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cron").value("@hourly"))
                .andExpect(jsonPath("$.description").value("Every hour, on the hour"));

        mvc.perform(delete("/workflow/api/schedules/nightly")).andExpect(status().isNoContent());
        mvc.perform(get("/workflow/api/schedules/nightly")).andExpect(status().isNotFound());
    }

    @Test
    void aFileOwnedScheduleIsOperatedButNotEdited() throws Exception {
        mvc.perform(put("/workflow/api/schedules/eom").contentType("application/json")
                        .content("{\"workflowId\": \"report\", \"cron\": \"@hourly\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("change it there")));
        mvc.perform(delete("/workflow/api/schedules/eom")).andExpect(status().isConflict());

        mvc.perform(post("/workflow/api/schedules/eom/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paused").value(true))
                .andExpect(jsonPath("$.pausedBy").value("alice"))
                .andExpect(jsonPath("$.state").value("PAUSED"));
        mvc.perform(post("/workflow/api/schedules/eom/resume"))
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        mvc.perform(post("/workflow/api/schedules/eom/run-now"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.businessKey").value("report-cron-manual-eom-2026-10-14T10:00:00.000Z"));
        assertThat(started).extracting(ProcessCreationRequested::workflowDefinitionId).containsExactly("report");
    }

    @Test
    void aCallerWhoMayNotStartTheWorkflowIsRefusedWith403LikeARefusedCreation() throws Exception {
        when(definitions.findAll()).thenReturn(List.of(new WorkflowDefinition("payout", "Payout", 1, "", false, 0, false,
                null, 0, List.of(), false, io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE,
                io.mateu.workflow.domain.aggregates.WorkflowStatus.ACTIVE, List.of("payments:write"), List.of(), 0, null)
                .withSchedules(List.of(new CronSchedule("eom", "0 0 23 L * ?", null, null)))));
        var service = new ScheduleService(definitions, new InMemoryCronScheduleRepository(),
                new InMemorySchedulePauseRepository(), started::add).withFlowAuthorization(true);
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) () -> new AuthorizationContext("alice", List.of(), List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new ScheduleRestController(service, apiProperties,
                beans.getBeanProvider(CallerResolver.class))).build();

        mvc.perform(post("/workflow/api/schedules").contentType("application/json")
                        .content("{\"id\": \"p\", \"workflowId\": \"payout\", \"cron\": \"0 0 9 * * *\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("payments:write")));
        mvc.perform(post("/workflow/api/schedules/eom/run-now")).andExpect(status().isForbidden());
        assertThat(started).isEmpty();
    }

    @Test
    void refusalsSayWhy() throws Exception {
        mvc.perform(post("/workflow/api/schedules").contentType("application/json")
                        .content("{\"id\": \"x\", \"workflowId\": \"ghost\", \"cron\": \"9 * *\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations[0]").value("workflow 'ghost' is unknown"))
                .andExpect(jsonPath("$.violations[1]", containsString("is not a valid cron expression")));
        mvc.perform(post("/workflow/api/schedules/ghost/pause")).andExpect(status().isNotFound());
    }

    @Test
    void exportsTheCronYamlEntry() throws Exception {
        mvc.perform(post("/workflow/api/schedules").contentType("application/json").content(NIGHTLY));

        mvc.perform(get("/workflow/api/schedules/nightly/yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(content().string(containsString("- id: nightly\n  cron: \"0 0 9 * * *\"\n  workflow: report\n")));
    }

    @Test
    void theApiKeyGuardsEveryEndpointWhenConfigured() throws Exception {
        apiProperties.setApiKey("secret");

        mvc.perform(get("/workflow/api/schedules")).andExpect(status().isUnauthorized());
        mvc.perform(post("/workflow/api/schedules/eom/run-now")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/schedules").header("X-Api-Key", "secret")).andExpect(status().isOk());
        assertThat(started).isEmpty();
    }
}
