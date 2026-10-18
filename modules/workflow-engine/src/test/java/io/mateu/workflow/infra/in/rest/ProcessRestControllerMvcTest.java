package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.readmodel.ProcessIndexRow;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.ProcessListingService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.create.CreateProcessCommand;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.LogMessage;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.dtos.events.integration.PauseProcessRequested;
import io.mateu.workflow.dtos.events.integration.RestartProcessRequested;
import io.mateu.workflow.dtos.events.integration.ResumeProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryProcessRequested;
import io.mateu.workflow.dtos.events.integration.RetryStepExecutionRequested;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryLogMessageRepository;
import io.mateu.workflow.infra.out.memory.InMemoryProcessRepositoryForTests;
import io.mateu.workflow.security.AuthorizationContext;
import io.mateu.workflow.security.CallerResolver;
import io.mateu.workflow.security.FlowAuthorizationDeniedException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every endpoint of {@code /workflow/api/processes} through the dispatcher, over the real
 * {@link ProcessOperationsService} and {@link ProcessListingService}: access, validation, the 404s and
 * 409s, paging and filters, and the commands each landing as the event the console and MCP send.
 */
class ProcessRestControllerMvcTest {

    static final String PROBLEM = "application/problem+json";

    final InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final InMemoryLogMessageRepository logs = new InMemoryLogMessageRepository();
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final CreateProcessUseCase createProcessUseCase = mock(CreateProcessUseCase.class);
    final List<DomainEvent> dispatched = new ArrayList<>();
    final MessageApiProperties apiProperties = new MessageApiProperties();
    final AtomicReference<AuthorizationContext> caller =
            new AtomicReference<>(new AuthorizationContext("alice", List.of(), List.of()));
    @SuppressWarnings("unchecked")
    final ObjectProvider<ProcessIndexRepository> index = mock(ObjectProvider.class);

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        apiProperties.setAllowAnonymous(true);
        // A default method, which Mockito does not run: routed to the real one, over findByProcessId.
        when(steps.findByProcess(any())).thenCallRealMethod();
        when(definitions.findById("orders")).thenReturn(Optional.of(definition("orders", List.of(), WorkflowStatus.ACTIVE)));
        when(definitions.findById("refunds")).thenReturn(Optional.of(definition("refunds", List.of(), WorkflowStatus.ACTIVE)));
        when(definitions.findById("retired")).thenReturn(Optional.of(definition("retired", List.of(), WorkflowStatus.DISABLED)));
        when(definitions.findAll()).thenReturn(List.of(
                definition("orders", List.of(), WorkflowStatus.ACTIVE).withTags(List.of("billing")),
                definition("refunds", List.of(), WorkflowStatus.ACTIVE)));
        // The use case creates the process it is asked for, as the real one does.
        doAnswer(invocation -> {
            CreateProcessCommand command = invocation.getArgument(0);
            processes.save(process(command.processId(), command.workflowDefinitionId(), command.businessKey(),
                    ProcessStatus.PENDING, LocalDateTime.now(), command.variables()));
            return null;
        }).when(createProcessUseCase).handle(any());
        mvc = mvc(false, false);
    }

    MockMvc mvc(boolean flowAuthorization, boolean readModel) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        var listing = new ProcessListingService(processes, index, new ProcessStatusAnnouncer(readModel, ""),
                new StaticListableBeanFactory(Map.of("definitions", definitions)).getBeanProvider(WorkflowDefinitionRepository.class));
        var service = new ProcessOperationsService(processes, steps, logs, definitions, createProcessUseCase,
                new CommandDispatcher(dispatched::add, dispatched::add, false), listing,
                new ProcessStatusAnnouncer(readModel, ""), index, flowAuthorization);
        var controller = new ProcessRestController(service, apiProperties, beans.getBeanProvider(CallerResolver.class));
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── Access ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void anAnonymousCallerIsRefusedUnlessTheEngineAllowsIt() throws Exception {
        apiProperties.setAllowAnonymous(false);
        mvc.perform(get("/workflow/api/processes"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail", containsString("refuses anonymous")));
        mvc.perform(post("/workflow/api/processes/p-1/cancel")).andExpect(status().isUnauthorized());
        assertThat(dispatched).isEmpty();
    }

    @Test
    void theApiKeyIsRequiredWhenOneIsConfigured() throws Exception {
        apiProperties.setApiKey("s3cret");
        processes.save(process("p-1", "orders", "bk-1", ProcessStatus.RUNNING, LocalDateTime.now(), List.of()));

        mvc.perform(get("/workflow/api/processes/p-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/processes/p-1").header("X-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/processes/p-1").header("X-Api-Key", "s3cret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p-1"));
    }

    @Test
    void aSecuredApplicationLetsItsAuthenticatedCallersIn() throws Exception {
        apiProperties.setAllowAnonymous(false);
        apiProperties.setSecuredByFilterChain(true);
        mvc.perform(get("/workflow/api/processes")).andExpect(status().isOk());
    }

    // ── Start ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void startsAProcessAndAStartWithTheSameKeyReturnsIt() throws Exception {
        var location = mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("""
                                {"definitionId": "orders", "businessKey": "ORDER-1",
                                 "variables": {"amount": "10", "lines": [1, 2], "express": true}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/workflow/api/processes/")))
                .andExpect(jsonPath("$.definitionId").value("orders"))
                .andExpect(jsonPath("$.businessKey").value("ORDER-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.variables.amount").value("10"))
                .andExpect(jsonPath("$.variables.lines").value("[1,2]"))
                .andExpect(jsonPath("$.variables.express").value("true"))
                .andReturn().getResponse().getHeader("Location");
        var id = location.substring(location.lastIndexOf('/') + 1);

        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"orders\", \"businessKey\": \"ORDER-1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/workflow/api/processes/" + id))
                .andExpect(jsonPath("$.id").value(id));
        assertThat(processes.findAll()).hasSize(1);

        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"refunds\", \"businessKey\": \"ORDER-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail", containsString("already taken")));
    }

    @Test
    void theCallerAndTheirTraceReachTheUseCase() throws Exception {
        var seen = new AtomicReference<CreateProcessCommand>();
        doAnswer(invocation -> {
            seen.set(invocation.getArgument(0));
            CreateProcessCommand command = invocation.getArgument(0);
            processes.save(process(command.processId(), "orders", null, ProcessStatus.PENDING, LocalDateTime.now(), List.of()));
            return null;
        }).when(createProcessUseCase).handle(any());

        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                        .content("{\"definitionId\": \"orders\"}"))
                .andExpect(status().isCreated());

        assertThat(seen.get().caller().subject()).isEqualTo("alice");
        assertThat(seen.get().businessKey()).isNull();
        assertThat(seen.get().traceContext().traceparent()).startsWith("00-4bf92f35");
    }

    @Test
    void aStartThatCannotBeAcceptedSaysWhy() throws Exception {
        mvc.perform(post("/workflow/api/processes").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail", containsString("definitionId")));
        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"nope\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"retired\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("DISABLED")));
        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"orders\", \"businessKey\": \"" + "k".repeat(5000) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("businessKey")));
        mvc.perform(post("/workflow/api/processes").contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/processes"))
                .andExpect(status().is4xxClientError());
        assertThat(processes.findAll()).isEmpty();
    }

    @Test
    void aCallerTheDefinitionRefusesIsForbidden() throws Exception {
        doThrow(new FlowAuthorizationDeniedException("Caller 'alice' may not start workflow 'orders': missing scope(s) [orders:start]"))
                .when(createProcessUseCase).handle(any());
        mvc.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"orders\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail", containsString("orders:start")));
    }

    // ── Reads ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void readsAProcessItsStepsVariablesAndLog() throws Exception {
        processes.save(process("p-1", "orders", "ORDER-7", ProcessStatus.RUNNING,
                LocalDateTime.of(2026, 10, 18, 9, 30), List.of(new Variable("amount", "12"))));
        when(steps.findByProcessId("p-1")).thenReturn(List.of(
                step("s-2", "p-1", "charge", StepExecutionStatus.RUNNING, 2),
                step("s-1", "p-1", "start", StepExecutionStatus.COMPLETED, 1)));
        logs.save(LogMessage.builder().id("l-2").processId("p-1").stepExecutionId("s-2").messageType("INFO")
                .message("charging").timestamp(LocalDateTime.of(2026, 10, 18, 9, 31)).build());
        logs.save(LogMessage.builder().id("l-1").processId("p-1").stepExecutionId("s-1").messageType("INFO")
                .message("started").timestamp(LocalDateTime.of(2026, 10, 18, 9, 30)).build());

        mvc.perform(get("/workflow/api/processes/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessKey").value("ORDER-7"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.created").value("2026-10-18T09:30:00"))
                .andExpect(jsonPath("$.variables.amount").value("12"));
        mvc.perform(get("/workflow/api/processes/by-key").param("businessKey", "ORDER-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("p-1"));
        mvc.perform(get("/workflow/api/processes/p-1/steps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].stepId", contains("start", "charge")))
                .andExpect(jsonPath("$[1].status").value("RUNNING"));
        mvc.perform(get("/workflow/api/processes/p-1/variables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value("12"));
        mvc.perform(get("/workflow/api/processes/p-1/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].message", contains("started", "charging")));
    }

    @Test
    void anUnknownProcessIsNotFoundEverywhere() throws Exception {
        for (var path : List.of("/workflow/api/processes/nope", "/workflow/api/processes/nope/steps",
                "/workflow/api/processes/nope/variables", "/workflow/api/processes/nope/logs")) {
            mvc.perform(get(path))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        }
        mvc.perform(get("/workflow/api/processes/by-key").param("businessKey", "nope"))
                .andExpect(status().isNotFound());
        for (var command : List.of("cancel", "pause", "resume", "retry", "restart", "steps/s-1/retry")) {
            mvc.perform(post("/workflow/api/processes/nope/" + command)).andExpect(status().isNotFound());
        }
        assertThat(dispatched).isEmpty();
    }

    @Test
    void listsPagesNewestFirst() throws Exception {
        for (int i = 0; i < 25; i++) {
            processes.save(process("p-" + i, i % 2 == 0 ? "orders" : "refunds", "BK-" + i,
                    i % 5 == 0 ? ProcessStatus.ERROR : ProcessStatus.COMPLETED,
                    LocalDateTime.of(2026, 10, 1, 0, 0).plusDays(i % 10).plusMinutes(i), List.of()));
        }

        mvc.perform(get("/workflow/api/processes").param("size", "10").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(25))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content", hasSize(5)));
        mvc.perform(get("/workflow/api/processes").param("size", "1"))
                .andExpect(jsonPath("$.content[0].id").value("p-19"))
                .andExpect(jsonPath("$.content[0].definitionId").value("refunds"))
                .andExpect(jsonPath("$.content[0].businessKey").value("BK-19"));

        mvc.perform(get("/workflow/api/processes").param("status", "error").param("definitionId", "orders"))
                .andExpect(jsonPath("$.totalElements").value(3)); // p-0, p-10, p-20
        mvc.perform(get("/workflow/api/processes").param("status", "ERROR,COMPLETED").param("tag", "BILLING"))
                .andExpect(jsonPath("$.totalElements").value(13));
        mvc.perform(get("/workflow/api/processes").param("tag", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/workflow/api/processes").param("businessKey", "bk-2"))
                .andExpect(jsonPath("$.totalElements").value(6)); // BK-2, BK-20..24
        mvc.perform(get("/workflow/api/processes").param("from", "2026-10-09").param("to", "2026-10-09"))
                .andExpect(jsonPath("$.totalElements").value(2)); // created on day 9: p-8, p-18
        mvc.perform(get("/workflow/api/processes").param("from", "2026-10-10T00:00:00"))
                .andExpect(jsonPath("$.totalElements").value(2)); // i%10==9 → p-9, p-19
    }

    @Test
    void aMalformedListingRequestIsABadRequest() throws Exception {
        for (var param : List.of(Map.of("status", "SLEEPING"), Map.of("size", "0"), Map.of("size", "501"),
                Map.of("page", "-1"), Map.of("page", "first"), Map.of("from", "yesterday"))) {
            var request = get("/workflow/api/processes");
            param.forEach(request::param);
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        }
    }

    @Test
    void withTheReadModelOnATextOnlyListingIsServedFromTheIndex() throws Exception {
        var indexStore = mock(ProcessIndexRepository.class);
        when(index.getIfAvailable()).thenReturn(indexStore);
        when(indexStore.search("ord", false, 0, 20)).thenReturn(Optional.of(new ProcessIndexRepository.ProcessIndexPage(
                List.of(indexRow("i-1", "ORD-1", "orders", "RUNNING")), 1, 0, 20)));

        mvc(false, true).perform(get("/workflow/api/processes").param("q", "ord"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("i-1"))
                .andExpect(jsonPath("$.content[0].businessKey").value("ORD-1"))
                .andExpect(jsonPath("$.content[0].definitionId").value("orders"));
    }

    // ── Commands ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void eachCommandIsDispatchedAsTheEventTheConsoleSends() throws Exception {
        processes.save(process("running", "orders", null, ProcessStatus.RUNNING, LocalDateTime.now(), List.of()));
        processes.save(process("paused", "orders", null, ProcessStatus.PAUSED, LocalDateTime.now(), List.of()));
        processes.save(process("failed", "orders", null, ProcessStatus.ERROR, LocalDateTime.now(), List.of()));
        when(steps.findById("s-err")).thenReturn(Optional.of(step("s-err", "failed", "charge", StepExecutionStatus.ERROR, 2)));

        mvc.perform(post("/workflow/api/processes/running/pause"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/workflow/api/processes/running"))
                .andExpect(jsonPath("$.command").value("pause"));
        mvc.perform(post("/workflow/api/processes/paused/resume")).andExpect(status().isAccepted());
        mvc.perform(post("/workflow/api/processes/running/cancel")).andExpect(status().isAccepted());
        mvc.perform(post("/workflow/api/processes/failed/retry")).andExpect(status().isAccepted());
        mvc.perform(post("/workflow/api/processes/failed/restart")).andExpect(status().isAccepted());
        mvc.perform(post("/workflow/api/processes/failed/steps/s-err/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stepExecutionId").value("s-err"));

        assertThat(dispatched).containsExactly(
                new PauseProcessRequested("running"),
                new ResumeProcessRequested("paused"),
                new ProcessCancellationRequested(null, "running"),
                new RetryProcessRequested("failed"),
                new RestartProcessRequested("failed"),
                new RetryStepExecutionRequested("s-err", "failed"));
    }

    @Test
    void aCommandTheStatusDoesNotAllowIsAConflict() throws Exception {
        processes.save(process("done", "orders", null, ProcessStatus.COMPLETED, LocalDateTime.now(), List.of()));
        processes.save(process("running", "orders", null, ProcessStatus.RUNNING, LocalDateTime.now(), List.of()));
        processes.save(process("cancelled", "orders", null, ProcessStatus.CANCELLED, LocalDateTime.now(), List.of()));
        when(steps.findById("s-ok")).thenReturn(Optional.of(step("s-ok", "running", "start", StepExecutionStatus.COMPLETED, 1)));
        when(steps.findById("s-err")).thenReturn(Optional.of(step("s-err", "cancelled", "charge", StepExecutionStatus.ERROR, 2)));
        when(steps.findById("s-other")).thenReturn(Optional.of(step("s-other", "done", "charge", StepExecutionStatus.ERROR, 2)));

        mvc.perform(post("/workflow/api/processes/done/cancel"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.processStatus").value("COMPLETED"));
        mvc.perform(post("/workflow/api/processes/done/pause")).andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/processes/running/resume")).andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/processes/running/retry")).andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/processes/done/restart")).andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/processes/running/steps/s-ok/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("COMPLETED")));
        mvc.perform(post("/workflow/api/processes/cancelled/steps/s-err/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("finished")));
        // A step of another process is not one of this process's steps.
        mvc.perform(post("/workflow/api/processes/running/steps/s-other/retry")).andExpect(status().isNotFound());
        assertThat(dispatched).isEmpty();
    }

    @Test
    void aCommandForAProcessAnotherShardOwnsIsRoutedFromTheIndex() throws Exception {
        var indexStore = mock(ProcessIndexRepository.class);
        when(index.getIfAvailable()).thenReturn(indexStore);
        when(indexStore.findByProcessId("remote")).thenReturn(Optional.of(indexRow("remote", "R-1", "orders", "ERROR")));

        mvc(false, true).perform(post("/workflow/api/processes/remote/retry")).andExpect(status().isAccepted());
        mvc(false, true).perform(post("/workflow/api/processes/remote/pause")).andExpect(status().isConflict());
        // With the read model off, the index is not trusted: unknown here is unknown.
        mvc.perform(post("/workflow/api/processes/remote/retry")).andExpect(status().isNotFound());

        assertThat(dispatched).containsExactly(new RetryProcessRequested("remote"));
    }

    // ── Flow authorization ───────────────────────────────────────────────────────────────────────

    @Test
    void withFlowAuthorizationOnADefinitionsRequirementsGateItsProcesses() throws Exception {
        when(definitions.findById("payroll")).thenReturn(Optional.of(
                definition("payroll", List.of("payroll:operate"), WorkflowStatus.ACTIVE)));
        processes.save(process("pay-1", "payroll", "PAY-1", ProcessStatus.ERROR, LocalDateTime.now(), List.of()));
        var secured = mvc(true, false);

        secured.perform(get("/workflow/api/processes/pay-1"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.detail", containsString("payroll:operate")));
        secured.perform(get("/workflow/api/processes/pay-1/variables")).andExpect(status().isForbidden());
        secured.perform(post("/workflow/api/processes/pay-1/retry")).andExpect(status().isForbidden());
        secured.perform(post("/workflow/api/processes").contentType("application/json")
                        .content("{\"definitionId\": \"payroll\", \"businessKey\": \"PAY-1\"}"))
                .andExpect(status().isForbidden());
        assertThat(dispatched).isEmpty();

        caller.set(new AuthorizationContext("bob", List.of("payroll:operate"), List.of()));
        secured.perform(get("/workflow/api/processes/pay-1")).andExpect(status().isOk());
        secured.perform(post("/workflow/api/processes/pay-1/retry")).andExpect(status().isAccepted());
        // Off, the same caller-less request reads it: the requirements are only enforced when asked to be.
        caller.set(null);
        mvc.perform(get("/workflow/api/processes/pay-1")).andExpect(status().isOk());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    static WorkflowDefinition definition(String id, List<String> requiredScopes, WorkflowStatus status) {
        return new WorkflowDefinition(id, id, 1, null, false, 0, false, null, 0, List.of(), false,
                WorkflowStatus.ACTIVE, status, requiredScopes, List.of(), 0, null);
    }

    static Process process(String id, String definitionId, String businessKey, ProcessStatus status,
                           LocalDateTime created, List<Variable> variables) {
        return Process.builder().id(id).name(definitionId).workflowDefinitionId(definitionId)
                .workflowDefinitionVersion(1).businessKey(businessKey).status(status).created(created)
                .variables(variables).build();
    }

    static StepExecution step(String id, String processId, String stepId, StepExecutionStatus status, long order) {
        return StepExecution.builder().id(id).processId(processId).stepId(stepId).status(status).order(order).build();
    }

    static ProcessIndexRow indexRow(String id, String businessKey, String definitionId, String status) {
        return new ProcessIndexRow(id, businessKey, "Orders", definitionId, 1, status, 50,
                LocalDateTime.of(2026, 10, 18, 9, 0), null, null, LocalDateTime.of(2026, 10, 18, 9, 0), "s1");
    }
}
