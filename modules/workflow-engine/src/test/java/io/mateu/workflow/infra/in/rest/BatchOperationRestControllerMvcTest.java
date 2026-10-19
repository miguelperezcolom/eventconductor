package io.mateu.workflow.infra.in.rest;

import io.mateu.workflow.application.out.ProcessIndexRepository;
import io.mateu.workflow.application.out.StepExecutionRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.application.out.WorkflowMetrics;
import io.mateu.workflow.application.services.BatchOperationService;
import io.mateu.workflow.application.services.CommandDispatcher;
import io.mateu.workflow.application.services.ProcessListingService;
import io.mateu.workflow.application.services.ProcessOperationsService;
import io.mateu.workflow.application.services.ProcessStatusAnnouncer;
import io.mateu.workflow.application.usecases.process.create.CreateProcessUseCase;
import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowStatus;
import io.mateu.workflow.dtos.events.domain.ProcessCancellationRequested;
import io.mateu.workflow.infra.config.BatchOperationProperties;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.InMemoryBatchOperationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /workflow/api/batch-operations} through the dispatcher, over the real batch service, the real
 * process operations and the in-memory stores — with flow authorization on, so a process the caller may
 * not operate is skipped, not failed. And the processes listing's {@code attr.*} and {@code query}
 * parameters, over processes whose definition declares search attributes.
 */
class BatchOperationRestControllerMvcTest {

    static final String DEFINITION_JSON = """
            {"id":"orders","searchAttributes":{"customerId":"${customerId}",
             "amount":{"value":"${total}","type":"number"}},"steps":[]}""";

    final InMemoryProcessRepositoryForTests processes = new InMemoryProcessRepositoryForTests();
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final List<DomainEvent> dispatched = new ArrayList<>();
    final InMemoryBatchOperationRepository batchStore = new InMemoryBatchOperationRepository();
    final AtomicReference<AuthorizationContext> caller =
            new AtomicReference<>(new AuthorizationContext("ana", List.of("orders"), List.of()));
    final MessageApiProperties apiProperties = new MessageApiProperties();
    BatchOperationService batches;
    MockMvc mvc;
    MockMvc processesMvc;

    @BeforeEach
    void setUp() {
        apiProperties.setApiKey("k");
        when(definitions.findById("orders")).thenReturn(Optional.of(
                ProcessRestControllerMvcTest.definition("orders", List.of("orders"), WorkflowStatus.ACTIVE)));
        when(definitions.findById("secret")).thenReturn(Optional.of(
                ProcessRestControllerMvcTest.definition("secret", List.of("secret"), WorkflowStatus.ACTIVE)));
        when(definitions.findAll()).thenReturn(List.of(
                ProcessRestControllerMvcTest.definition("orders", List.of(), WorkflowStatus.ACTIVE).withTags(List.of("billing"))));
        save("p1", "orders", ProcessStatus.RUNNING, "C-9", "150");
        save("p2", "orders", ProcessStatus.RUNNING, "C-9", "50");
        save("p3", "orders", ProcessStatus.COMPLETED, "C-9", "70");
        save("p4", "secret", ProcessStatus.RUNNING, "C-9", "10");
        save("p5", "orders", ProcessStatus.RUNNING, "C-7", "500");

        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProcessIndexRepository> index = mock(ObjectProvider.class);
        var listing = new ProcessListingService(processes, index, new ProcessStatusAnnouncer(false, ""),
                new StaticListableBeanFactory(Map.of("definitions", definitions)).getBeanProvider(WorkflowDefinitionRepository.class));
        var operations = new ProcessOperationsService(processes, mock(StepExecutionRepository.class),
                new InMemoryLogMessageRepository(), definitions, mock(CreateProcessUseCase.class),
                new CommandDispatcher(dispatched::add, dispatched::add, false), listing,
                new ProcessStatusAnnouncer(false, ""), index, true);
        var properties = new BatchOperationProperties();
        properties.setMaxPerSecond(0);
        batches = new BatchOperationService(batchStore, processes, listing, operations, properties,
                new StaticListableBeanFactory().getBeanProvider(WorkflowMetrics.class));
        mvc = MockMvcBuilders.standaloneSetup(new BatchOperationRestController(batches, apiProperties,
                beans.getBeanProvider(CallerResolver.class))).build();
        processesMvc = MockMvcBuilders.standaloneSetup(new ProcessRestController(operations, apiProperties,
                beans.getBeanProvider(CallerResolver.class))).build();
    }

    void save(String id, String definition, ProcessStatus status, String customerId, String total) {
        processes.save(Process.builder().id(id).name(definition).workflowDefinitionId(definition)
                .workflowDefinitionVersion(1).workflowDefinitionJson(DEFINITION_JSON).businessKey("bk-" + id)
                .status(status).created(LocalDateTime.now())
                .variables(List.of(new Variable("customerId", customerId), new Variable("total", total))).build());
    }

    @Test
    void aBatchIsCreatedRunReadAndRefusedOnceFinished() throws Exception {
        var created = mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k")
                        .contentType("application/json").content("""
                                {"type": "cancel", "filter": {"attributes": {"customerId": "C-9"}, "query": "amount < 100"}}"""))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", containsString("/workflow/api/batch-operations/")))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.matched").value(3))
                .andExpect(jsonPath("$.filter.query").value("customerId = C-9 AND amount < 100"))
                .andExpect(jsonPath("$.createdBy").value("ana"))
                .andReturn().getResponse();
        var id = com.jayway.jsonpath.JsonPath.read(created.getContentAsString(), "$.id").toString();

        while (batches.runNextPage()) {
            // to the end
        }

        mvc.perform(get("/workflow/api/batch-operations/" + id).header("X-Api-Key", "k"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.skippedByReason.notApplicable").value(1))
                .andExpect(jsonPath("$.skippedByReason.notAuthorized").value(1))
                .andExpect(jsonPath("$.percent").value(100));
        // Only p2 (RUNNING, C-9, 50, authorized) was commanded: p3 is COMPLETED, p4 is not the caller's.
        assertThat(dispatched).singleElement().isInstanceOfSatisfying(ProcessCancellationRequested.class,
                command -> assertThat(command.processId()).isEqualTo("p2"));

        mvc.perform(get("/workflow/api/batch-operations").header("X-Api-Key", "k"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/workflow/api/batch-operations/" + id + "/cancel").header("X-Api-Key", "k"))
                .andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/batch-operations/nope/cancel").header("X-Api-Key", "k"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/workflow/api/batch-operations/nope").header("X-Api-Key", "k"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMigrateBatchCarriesItsVersionsAndMapping() throws Exception {
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                        .content("""
                                {"type": "MIGRATE", "filter": {"definitionId": "orders", "status": ["RUNNING"]},
                                 "targetVersion": 3, "fromVersion": 2, "stepMapping": {"charge": "charge-card"},
                                 "variables": {"currency": "EUR"}, "reason": "v3 fixes the charge"}"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("MIGRATE"))
                .andExpect(jsonPath("$.parameters.targetVersion").value("3"))
                .andExpect(jsonPath("$.parameters.fromVersion").value("2"))
                .andExpect(jsonPath("$['parameters']['map.charge']").value("charge-card"))
                .andExpect(jsonPath("$['parameters']['var.currency']").value("EUR"));
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                        .content("{\"type\": \"MIGRATE\", \"filter\": {\"tag\": \"billing\"}, \"reason\": \"r\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("must name the definition")));
    }

    @Test
    void anActiveBatchIsCancelled() throws Exception {
        var batch = batches.create(io.mateu.workflow.domain.batch.BatchOperationType.PAUSE,
                new io.mateu.workflow.domain.batch.BatchOperationFilter("orders", null, null, null, null, null, null,
                        false, null), null, null);
        mvc.perform(post("/workflow/api/batch-operations/" + batch.id() + "/cancel").header("X-Api-Key", "k"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(batches.runNextPage()).isFalse();
    }

    @Test
    void whatIsNotABatchIsRefused() throws Exception {
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"type\": \"cancel\", \"filter\": {}}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("needs a filter")));
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"type\": \"explode\", \"filter\": {\"definitionId\": \"orders\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"filter\": {\"definitionId\": \"orders\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"type\": \"cancel\", \"filter\": {\"status\": [\"NAPPING\"]}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"type\": \"cancel\", \"filter\": {\"query\": \"amount >\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json")
                .content("{\"type\": \"cancel\", \"filter\": {\"from\": \"yesterday\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post("/workflow/api/batch-operations").header("X-Api-Key", "k").contentType("application/json"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/workflow/api/batch-operations")).andExpect(status().isUnauthorized());
    }

    @Test
    void theListingFiltersByAttributeParametersAndTheQuery() throws Exception {
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k")
                        .param("attr.customerId", "C-9").param("attr.amount.gte", "50").param("status", "RUNNING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k")
                        .param("query", "customerId = C-9 AND amount > 100"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value("p1"));
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k")
                        .param("attr.amount.lte", "70").param("attr.amount.gt", "50").param("tag", "billing"))
                .andExpect(jsonPath("$.totalElements").value(1));
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k").param("attr.amount.like", "5"))
                .andExpect(status().isBadRequest());
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k").param("attr.bad name", "5"))
                .andExpect(status().isBadRequest());
        processesMvc.perform(get("/workflow/api/processes").header("X-Api-Key", "k").param("query", "a = \"open"))
                .andExpect(status().isBadRequest());
        processesMvc.perform(get("/workflow/api/processes/p1").header("X-Api-Key", "k"))
                .andExpect(jsonPath("$.searchAttributes.customerId").value("C-9"))
                .andExpect(jsonPath("$.searchAttributes.amount").value("150"));
    }
}
