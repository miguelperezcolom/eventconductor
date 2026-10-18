package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.ProcessListingService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.domain.services.CompensationService;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.dtos.events.integration.CompleteStepManuallyRequested;
import io.mateu.workflow.dtos.events.integration.RetryFromStepRequested;
import io.mateu.workflow.dtos.events.integration.SetProcessVariablesRequested;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryLogMessageRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.mateu.workflow.infra.in.rest.ProcessRestControllerMvcTest.definition;
import static io.mateu.workflow.infra.in.rest.ProcessRestControllerMvcTest.process;
import static io.mateu.core.infra.JsonSerializer.toJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The manual operations of {@code /workflow/api/processes} — set variables, complete a step, retry from a
 * step — through the dispatcher over the real {@link ProcessOperationsService}: validation, the 404s and
 * 409s decided by {@link ProcessOperationRules}, the authorization (flow authorization and the
 * operations role), and each landing as its requested event carrying the caller and the reason.
 */
class ProcessOperationsRestMvcTest {

    final InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final List<DomainEvent> dispatched = new ArrayList<>();
    final MessageApiProperties apiProperties = new MessageApiProperties();
    final AtomicReference<AuthorizationContext> caller =
            new AtomicReference<>(new AuthorizationContext("alice", List.of(), List.of()));
    @SuppressWarnings("unchecked")
    final ObjectProvider<ProcessIndexRepository> index = mock(ObjectProvider.class);

    @BeforeEach
    void setUp() {
        apiProperties.setAllowAnonymous(true);
        when(steps.findByProcess(any())).thenCallRealMethod();
        when(definitions.findById("orders")).thenReturn(Optional.of(definition("orders", List.of(), WorkflowStatus.ACTIVE)));
        when(definitions.findById("payroll")).thenReturn(Optional.of(
                definition("payroll", List.of("payroll:operate"), WorkflowStatus.ACTIVE)));
        processes.save(process("failed", "orders", "F-1", ProcessStatus.ERROR, LocalDateTime.now(), List.of()));
        processes.save(process("done", "orders", "D-1", ProcessStatus.COMPLETED, LocalDateTime.now(), List.of()));
        processes.save(process("pay-1", "payroll", "P-1", ProcessStatus.ERROR, LocalDateTime.now(), List.of()));
        var start = step("s-start", "failed", "start", StepExecutionStatus.COMPLETED, 0, "START", null);
        var charge = step("s-charge", "failed", "charge", StepExecutionStatus.ERROR, 1, "ACTION", "start");
        var ship = step("s-ship", "failed", "ship", StepExecutionStatus.CREATED, 2, "ACTION", "charge");
        when(steps.findByProcessId("failed")).thenReturn(List.of(start, charge, ship));
        when(steps.findById("s-charge")).thenReturn(Optional.of(charge));
        when(steps.findById("s-ship")).thenReturn(Optional.of(ship));
        var payStep = step("s-pay", "pay-1", "pay", StepExecutionStatus.ERROR, 1, "ACTION", null);
        when(steps.findByProcessId("pay-1")).thenReturn(List.of(payStep));
        when(steps.findById("s-pay")).thenReturn(Optional.of(payStep));
        when(steps.findByProcessId("done")).thenReturn(List.of());
    }

    MockMvc mvc(boolean flowAuthorization, List<String> operationsRoles) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        var listing = new ProcessListingService(processes, index, new ProcessStatusAnnouncer(false, ""),
                new StaticListableBeanFactory(Map.of("definitions", definitions)).getBeanProvider(WorkflowDefinitionRepository.class));
        var service = new ProcessOperationsService(processes, steps, new InMemoryLogMessageRepository(), definitions,
                mock(CreateProcessUseCase.class), new CommandDispatcher(dispatched::add, dispatched::add, false), listing,
                new ProcessStatusAnnouncer(false, ""), index, flowAuthorization,
                new ProcessOperationRules(new CompensationService()), operationsRoles);
        var controller = new ProcessRestController(service, apiProperties, beans.getBeanProvider(CallerResolver.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void eachOperationIsDispatchedWithTheCallerAndTheReason() throws Exception {
        var mvc = mvc(false, List.of());

        mvc.perform(post("/workflow/api/processes/failed/variables").contentType("application/json")
                        .content("{\"set\": {\"amount\": 12, \"note\": \"ok\"}, \"remove\": [\"tmp\"], \"reason\": \"fix\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.command").value("set-variables"));
        mvc.perform(post("/workflow/api/processes/failed/steps/s-charge/complete").contentType("application/json")
                        .content("{\"variables\": {\"ref\": \"R1\"}, \"reason\": \"paid by phone\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stepExecutionId").value("s-charge"));
        mvc.perform(post("/workflow/api/processes/failed/retry-from/charge").contentType("application/json")
                        .content("{\"reason\": \"fixed\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stepId").value("charge"));

        assertThat(dispatched).containsExactly(
                new SetProcessVariablesRequested("failed", List.of(new Variable("amount", "12"), new Variable("note", "ok")),
                        List.of("tmp"), "fix", "alice"),
                new CompleteStepManuallyRequested("failed", "s-charge", List.of(new Variable("ref", "R1")),
                        "paid by phone", "alice"),
                new RetryFromStepRequested("failed", "charge", "fixed", "alice"));
    }

    @Test
    void aMissingReasonOrBodyIsABadRequest() throws Exception {
        var mvc = mvc(false, List.of());
        mvc.perform(post("/workflow/api/processes/failed/retry-from/charge").contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("reason")));
        mvc.perform(post("/workflow/api/processes/failed/variables")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes/failed/variables").contentType("application/json")
                .content("{\"reason\": \"nothing\"}")).andExpect(status().isBadRequest());
        assertThat(dispatched).isEmpty();
    }

    @Test
    void unknownProcessesAndStepsAreNotFoundAndRefusalsAreConflicts() throws Exception {
        var mvc = mvc(false, List.of());
        mvc.perform(post("/workflow/api/processes/nope/variables").contentType("application/json")
                .content("{\"set\": {\"a\": \"1\"}, \"reason\": \"r\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/workflow/api/processes/failed/steps/nope/complete").contentType("application/json")
                .content("{\"reason\": \"r\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/workflow/api/processes/failed/retry-from/nope").contentType("application/json")
                .content("{\"reason\": \"r\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/workflow/api/processes/done/variables").contentType("application/json")
                        .content("{\"set\": {\"a\": \"1\"}, \"reason\": \"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.processStatus").value("COMPLETED"));
        mvc.perform(post("/workflow/api/processes/failed/steps/s-ship/complete").contentType("application/json")
                        .content("{\"reason\": \"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("CREATED")));
        mvc.perform(post("/workflow/api/processes/failed/retry-from/ship").contentType("application/json")
                        .content("{\"reason\": \"r\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("has not run yet")));
        assertThat(dispatched).isEmpty();
    }

    @Test
    void theOperationsRoleGatesEveryManualOperation() throws Exception {
        var mvc = mvc(false, List.of("ops-admin"));
        mvc.perform(post("/workflow/api/processes/failed/retry-from/charge").contentType("application/json")
                        .content("{\"reason\": \"r\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail", containsString("ops-admin")));
        caller.set(null);
        mvc.perform(post("/workflow/api/processes/failed/variables").contentType("application/json")
                .content("{\"set\": {\"a\": \"1\"}, \"reason\": \"r\"}")).andExpect(status().isForbidden());
        assertThat(dispatched).isEmpty();

        caller.set(new AuthorizationContext("ops", List.of(), List.of("ops-admin")));
        mvc.perform(post("/workflow/api/processes/failed/retry-from/charge").contentType("application/json")
                .content("{\"reason\": \"r\"}")).andExpect(status().isAccepted());
        assertThat(dispatched).containsExactly(new RetryFromStepRequested("failed", "charge", "r", "ops"));
    }

    @Test
    void withFlowAuthorizationTheDefinitionsRequirementsGateTheOperations() throws Exception {
        var mvc = mvc(true, List.of());
        mvc.perform(post("/workflow/api/processes/pay-1/steps/s-pay/complete").contentType("application/json")
                .content("{\"reason\": \"r\"}")).andExpect(status().isForbidden());

        caller.set(new AuthorizationContext("bob", List.of("payroll:operate"), List.of()));
        mvc.perform(post("/workflow/api/processes/pay-1/steps/s-pay/complete").contentType("application/json")
                .content("{\"reason\": \"r\"}")).andExpect(status().isAccepted());
        assertThat(dispatched).hasSize(1);
    }

    static StepExecution step(String id, String processId, String stepId, StepExecutionStatus status, long order,
                              String type, String after) {
        var json = new java.util.LinkedHashMap<String, Object>();
        json.put("id", stepId);
        json.put("type", type);
        if (after != null) {
            json.put("preconditionStepId", after);
        }
        return StepExecution.builder().id(id).processId(processId).stepId(stepId).status(status).order(order)
                .workflowDefinitionId("orders").stepJson(toJson(json))
                .startedAt(status == StepExecutionStatus.CREATED ? null : LocalDateTime.now()).build();
    }
}
