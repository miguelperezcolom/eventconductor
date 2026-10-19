package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.ProcessListingService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.operate.MigrateProcessUseCaseTest;
import io.mateu.workflow.application.usecases.process.operate.MigrationPlanner;
import io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.events.integration.MigrateProcessRequested;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.in.mcp.ProcessOperationsMcpTools;
import io.mateu.workflow.infra.out.memory.InMemoryLogMessageRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import io.mateu.workflow.application.out.WorkflowDefinitionHistory;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.mateu.core.infra.JsonSerializer.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /workflow/api/processes/{id}/migrate} and its dry run over the real {@link ProcessOperationsService}
 * and {@link MigrationPlanner}: the plan as JSON, the 409 with the problems, the 400s, the request landing as a
 * {@link MigrateProcessRequested} with the caller and the reason — and the same through the MCP tools.
 */
class MigrationRestMvcTest {

    final InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final List<DomainEvent> dispatched = new ArrayList<>();
    final MessageApiProperties apiProperties = new MessageApiProperties();
    final AtomicReference<AuthorizationContext> caller =
            new AtomicReference<>(new AuthorizationContext("alice", List.of(), List.of()));
    @SuppressWarnings("unchecked")
    final ObjectProvider<ProcessIndexRepository> index = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    final ObjectProvider<WorkflowDefinitionHistory> versions = mock(ObjectProvider.class);
    ProcessOperationsService service;

    @BeforeEach
    void setUp() {
        apiProperties.setAllowAnonymous(true);
        when(steps.findByProcess(any())).thenCallRealMethod();
        var v1 = MigrateProcessUseCaseTest.definition(1, MigrateProcessUseCaseTest.v1());
        processes.save(Process.builder().id("p").workflowDefinitionId("w").workflowDefinitionVersion(1)
                .workflowDefinitionJson(toJson(v1)).status(ProcessStatus.RUNNING).variables(List.of()).build());
        processes.save(Process.builder().id("done").workflowDefinitionId("w").workflowDefinitionVersion(1)
                .workflowDefinitionJson(toJson(v1)).status(ProcessStatus.COMPLETED).variables(List.of()).build());
        var v1Steps = MigrateProcessUseCaseTest.v1();
        when(steps.findByProcessId("p")).thenReturn(List.of(
                MigrateProcessUseCaseTest.execution(v1Steps.get(0), StepExecutionStatus.COMPLETED, 1),
                MigrateProcessUseCaseTest.execution(v1Steps.get(1), StepExecutionStatus.COMPLETED, 2),
                MigrateProcessUseCaseTest.execution(v1Steps.get(2), StepExecutionStatus.PENDING, 3),
                MigrateProcessUseCaseTest.execution(v1Steps.get(3), StepExecutionStatus.CREATED, 4),
                MigrateProcessUseCaseTest.execution(v1Steps.get(4), StepExecutionStatus.CREATED, 5)));
        when(steps.findByProcessId("done")).thenReturn(List.of());
        var v2 = MigrateProcessUseCaseTest.definition(2, List.of(MigrateProcessUseCaseTest.step("start", "START", null),
                MigrateProcessUseCaseTest.step("a", "ACTION", "start", "topic", "work"),
                MigrateProcessUseCaseTest.step("b2", "ACTION", "a", "topic", "work"),
                MigrateProcessUseCaseTest.step("c", "ACTION", "b2", "topic", "work"),
                MigrateProcessUseCaseTest.step("end", "END", "c")));
        when(definitions.findById("w")).thenReturn(Optional.of(v2));
    }

    MockMvc mvc(List<String> operationsRoles) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        var listing = new ProcessListingService(processes, index, new ProcessStatusAnnouncer(false, ""),
                new StaticListableBeanFactory(Map.of("definitions", definitions)).getBeanProvider(WorkflowDefinitionRepository.class));
        var rules = new ProcessOperationRules(new CompensationService());
        service = new ProcessOperationsService(processes, steps, new InMemoryLogMessageRepository(), definitions,
                mock(CreateProcessUseCase.class), new CommandDispatcher(dispatched::add, dispatched::add, false), listing,
                new ProcessStatusAnnouncer(false, ""), index, false, rules, operationsRoles);
        service.setMigrationPlanner(new MigrationPlanner(definitions, versions, rules, 0));
        var controller = new ProcessRestController(service, apiProperties, beans.getBeanProvider(CallerResolver.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void theDryRunAnswersThePlanAndChangesNothing() throws Exception {
        var mvc = mvc(List.of());
        mvc.perform(post("/workflow/api/processes/p/migrate/dry-run").contentType("application/json")
                        .content("{\"stepMapping\": {\"b\": \"b2\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executable").value(true))
                .andExpect(jsonPath("$.fromVersion").value(1))
                .andExpect(jsonPath("$.toVersion").value(2))
                .andExpect(jsonPath("$.mapping.b").value("b2"))
                .andExpect(jsonPath("$.steps[?(@.sourceStepId == 'b')].action").value("KEEP_TASK"));
        mvc.perform(post("/workflow/api/processes/p/migrate/dry-run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executable").value(false))
                .andExpect(jsonPath("$.problems[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.problems[0].stepId").value("b"));
        assertThat(dispatched).isEmpty();
    }

    @Test
    void aMigrationIsDispatchedWithTheCallerAndTheReason() throws Exception {
        mvc(List.of()).perform(post("/workflow/api/processes/p/migrate").contentType("application/json")
                        .content("{\"targetVersion\": 2, \"stepMapping\": {\"b\": \"b2\"}, \"variableMapping\": {\"x\": \"1\"},"
                                + " \"reason\": \"fix\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command").value("migrate"));
        assertThat(dispatched).containsExactly(new MigrateProcessRequested("p", 2, Map.of("b", "b2"), Map.of("x", "1"),
                "fix", "alice"));
    }

    @Test
    void refusalsAndBadRequests() throws Exception {
        var mvc = mvc(List.of());
        mvc.perform(post("/workflow/api/processes/p/migrate").contentType("application/json")
                        .content("{\"reason\": \"no mapping\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("'b' is ACTIVE")));
        mvc.perform(post("/workflow/api/processes/done/migrate").contentType("application/json")
                        .content("{\"reason\": \"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("only a RUNNING, PAUSED or ERROR")));
        mvc.perform(post("/workflow/api/processes/p/migrate").contentType("application/json")
                .content("{\"stepMapping\": {\"b\": \"b2\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/p/migrate")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/p/migrate").contentType("application/json")
                        .content("{\"targetVersion\": 7, \"reason\": \"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("no version history")));
        mvc.perform(post("/workflow/api/processes/p/migrate").contentType("application/json")
                .content("{\"variableMapping\": {\" \": \"1\"}, \"reason\": \"r\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/nope/migrate/dry-run")).andExpect(status().isNotFound());
        assertThat(dispatched).isEmpty();
    }

    @Test
    void theOperationsRoleGatesTheMigration() throws Exception {
        mvc(List.of("ops-admin")).perform(post("/workflow/api/processes/p/migrate/dry-run"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProcessOnAnotherNodeIsPlannedThere() {
        mvc(List.of());
        // Not local and no read model: unknown here.
        assertThatThrownBy(() -> service.dryRunMigration("elsewhere", null, null, null, caller.get()))
                .isInstanceOf(ProcessOperationsService.ProcessNotFoundException.class);
        assertThat(service.migrationVersions("w")).containsExactly(2);
        service.setMigrationPlanner(null);
        assertThat(service.migrationVersions("w")).isEmpty();
        assertThatThrownBy(() -> service.dryRunMigration("p", null, null, null, caller.get()))
                .isInstanceOf(ProcessOperationsService.ProcessStateConflictException.class)
                .hasMessageContaining("owned by another node");
        // Without a planner here the owning node plans it: a live process is passed on.
        service.migrate("p", null, null, null, "r", caller.get());
        assertThat(dispatched).hasSize(1);
        assertThatThrownBy(() -> service.migrate("done", null, null, null, "r", caller.get()))
                .isInstanceOf(ProcessOperationsService.ProcessStateConflictException.class);
    }

    @Test
    void theMcpToolsSayWhatTheyDidOrWhyNot() {
        mvc(List.of());
        var tools = new ProcessOperationsMcpTools(service, mock(io.mateu.workflow.application.services.IncidentService.class));
        var plan = (ProcessOperationsMcpTools.MigrationPlanSummary) tools.dryRunProcessMigration("p", null,
                Map.of("b", "b2"), null);
        assertThat(plan.executable()).isTrue();
        assertThat(plan.steps()).anyMatch(line -> line.startsWith("b → b2: KEEP_TASK"));
        assertThat(tools.dryRunProcessMigration("nope", null, null, null)).asString().startsWith("Refused:");
        assertThat(tools.migrateProcess("p", null, Map.of("b", "b2"), null, "fix")).contains("requested for process p");
        assertThat(tools.migrateProcess("p", 3, Map.of("b", "b2"), null, "fix")).startsWith("Refused:");
        assertThat(dispatched).hasSize(1);
    }
}
