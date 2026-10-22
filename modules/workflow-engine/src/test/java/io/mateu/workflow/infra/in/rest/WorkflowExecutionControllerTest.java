package io.mateu.workflow.infra.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.application.out.*;
import io.mateu.workflow.application.services.*;
import io.mateu.workflow.application.usecases.process.create.*;
import io.mateu.workflow.domain.aggregates.*;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.infra.config.MessageApiProperties;
import io.mateu.workflow.infra.out.memory.*;
import io.mateu.workflow.infra.out.persistence.JsonProcessSnapshotReader;
import io.mateu.workflow.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mateu.workflow.application.usecases.process.operate.ProcessOperationRules;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real validation, authorization, idempotency and graph projection through HTTP, without workers. */
class WorkflowExecutionControllerTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final InMemoryProcessRepositoryForTests store = new InMemoryProcessRepositoryForTests();
    final StepExecutionRepository steps = mock(StepExecutionRepository.class);
    final WorkflowDefinitionRepository definitions = mock(WorkflowDefinitionRepository.class);
    final CreateProcessUseCase creator = mock(CreateProcessUseCase.class);
    final MessageApiProperties access = new MessageApiProperties();
    final JsonProcessSnapshotReader snapshots = new JsonProcessSnapshotReader();
    final AtomicReference<AuthorizationContext> caller = new AtomicReference<>(new AuthorizationContext("alice", List.of(), List.of(TESTER)));
    static final String TESTER = "workflow-tester";
    MockMvc mvc;
    ProcessOperationsService operations;
    final String runId = "a7d8b95a-eae4-4416-b38b-e85a6f0a24db";
    static final String DOCUMENT = """
        {"id":"draft","name":"Draft","version":7,"steps":[
          {"id":"start","type":"START","name":"Start"},
          {"id":"end","type":"END","name":"End","preconditionStepId":"start"}]}
        """;

    @BeforeEach void setUp() {
        access.setAllowAnonymous(true);
        when(steps.findByProcess(any())).thenCallRealMethod();
        doAnswer(call -> {
            CreateProcessCommand c = call.getArgument(0);
            store.save(Process.create(c.processId(), c.definition(), c.businessKey(), c.variables())
                .withTestRun(c.testRun()).withTestRunRequestDigest(c.testRunRequestDigest()));
            return null;
        }).when(creator).handle(any());
        mvc = mvc(true, false);
    }

    MockMvc mvc(boolean enabled, boolean authorization) {
        return mvc(enabled, authorization, List.of(TESTER));
    }

    MockMvc mvc(boolean enabled, boolean authorization, List<String> testerRoles) {
        var beans = new StaticListableBeanFactory();
        beans.addBean("caller", (CallerResolver) caller::get);
        var index = beans.getBeanProvider(ProcessIndexRepository.class);
        var listing = new ProcessListingService(store, index, new ProcessStatusAnnouncer(false, ""),
            new StaticListableBeanFactory(Map.of("definitions", definitions)).getBeanProvider(WorkflowDefinitionRepository.class));
        operations = new ProcessOperationsService(store, steps, new InMemoryLogMessageRepository(), definitions, creator,
            new CommandDispatcher(event -> {}, event -> {}, false), listing, new ProcessStatusAnnouncer(false, ""), index, authorization,
            new ProcessOperationRules(new io.mateu.workflow.domain.services.CompensationService()), List.of(),
            new StaticListableBeanFactory(Map.of("snapshots", snapshots)).getBeanProvider(ProcessSnapshotReader.class));
        var service = new WorkflowTestRunService(store, definitions, creator, (id, action) -> { synchronized(store) { action.run(); } },
            snapshots, authorization, testerRoles);
        var validator = new WorkflowDefinitionValidator();
        org.springframework.test.util.ReflectionTestUtils.setField(validator, "encryption", SensitiveFixtures.keys());
        return MockMvcBuilders.standaloneSetup(new WorkflowExecutionController(service, operations, store, steps,
            snapshots, validator, access, beans.getBeanProvider(CallerResolver.class), enabled),
            new ProcessRestController(operations, access, beans.getBeanProvider(CallerResolver.class))).build();
    }

    String request(String document, Map<String,Object> variables) throws Exception {
        return json.writeValueAsString(Map.of("runId", runId, "definition", json.readTree(document), "variables", variables));
    }

    @Test void disabledByDefaultAndUsesTheNormalApiAccessGate() throws Exception {
        mvc(false, false).perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail", containsString("disabled")));
        access.setAllowAnonymous(false);
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/workflow/api/processes/missing/graph")).andExpect(status().isUnauthorized());
        verifyNoInteractions(creator);
    }

    @Test void aRepeatedSubmissionReturnsTheSameFrozenRunAndNeverPublishesADefinition() throws Exception {
        var body = request(DOCUMENT, Map.of("items", List.of(1,2), "amount", 10));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.definitionVersion").value(7))
            .andExpect(jsonPath("$.definitionId").value("vscode-draft-"+runId))
            .andExpect(jsonPath("$.variables.items").value("[1,2]"));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(runId));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of("amount", 11))))
            .andExpect(status().isConflict());
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT.replace("Draft", "Edited"), Map.of())))
            .andExpect(status().isConflict());
        verify(creator, times(1)).handle(any());
        verify(definitions, never()).save(any());
        assertThat(store.findById(runId).orElseThrow().isTestRun()).isTrue();
        assertThat(snapshots.read(store.findById(runId).orElseThrow().getWorkflowDefinitionJson()).name()).isEqualTo("Draft");
    }

    @Test void aDocumentWithoutAnIdGetsAnIsolatedIdAndItsStepsAreBoundToIt() throws Exception {
        String document = DOCUMENT.replace("\"id\":\"draft\",", "");
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(document, Map.of())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.definitionId").value("vscode-"+runId));
        var definition = snapshots.read(store.findById(runId).orElseThrow().getWorkflowDefinitionJson());
        assertThat(definition.steps()).allSatisfy(step -> assertThat(step.workflowDefinitionId()).isEqualTo(definition.id()));
        verify(definitions, never()).save(any());
    }

    @Test void rejectsTyposMalformedIdsAndAnyLatestContinuation() throws Exception {
        for (String document : List.of(DOCUMENT.replace("\"version\":7", "\"versoin\":7"),
            DOCUMENT.replace("\"type\":\"END\"", "\"type\":\"AGENT_CALL\""),
            DOCUMENT.replace("\"type\":\"END\"", "\"type\":\"CONTINUE_AS_NEW\",\"continueAsNew\":{\"version\":\"latest\"}"))) {
            mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(document, Map.of())))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of()).replace(runId, "../bad")))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(creator);
    }

    @Test void aTestRunNeedsTheConfiguredRoleEvenWithFlowAuthorizationOff() throws Exception {
        caller.set(new AuthorizationContext("mallory", List.of(), List.of()));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isForbidden());
        caller.set(null);
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isForbidden());
        caller.set(new AuthorizationContext("alice", List.of(), List.of(TESTER)));
        mvc(true, false, List.of()).perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail", containsString("workflow.test-runs.required-roles")));
        verifyNoInteractions(creator);
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isCreated());
    }

    @Test void theDocumentCannotGrantItselfTheTestRole() throws Exception {
        caller.set(new AuthorizationContext("mallory", List.of(), List.of("self-declared")));
        var document = DOCUMENT.replace("\"version\":7", "\"version\":7,\"requiredRoles\":[\"self-declared\"]");
        mvc(true, true).perform(post("/workflow/api/test-runs").contentType("application/json").content(request(document, Map.of())))
            .andExpect(status().isForbidden());
        verifyNoInteractions(creator);
    }

    @Test void aPublishedIdRunsUnderItsOwnIdAndIsNeverMigrated() throws Exception {
        when(definitions.findById("draft")).thenReturn(Optional.of(json.readValue(DOCUMENT, WorkflowDefinition.class)));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.definitionId").value("vscode-draft-"+runId));
        var run = store.findById(runId).orElseThrow();
        assertThat(run.getWorkflowDefinitionId()).isEqualTo("vscode-draft-"+runId);
        assertThat(snapshots.read(run.getWorkflowDefinitionJson()).steps())
            .allSatisfy(step -> assertThat(step.workflowDefinitionId()).isEqualTo("vscode-draft-"+runId));
        assertThatThrownBy(() -> operations.dryRunMigration(runId, null, Map.of(), Map.of(), caller.get()))
            .isInstanceOf(ProcessOperationsService.ProcessStateConflictException.class).hasMessageContaining("test run");
        assertThatThrownBy(() -> operations.migrate(runId, null, Map.of(), Map.of(), "synthetic", caller.get()))
            .isInstanceOf(ProcessOperationsService.ProcessStateConflictException.class).hasMessageContaining("test run");
    }

    @Test void aPublishedDefinitionHoldingTheTestRunIdIsAConflict() throws Exception {
        when(definitions.findById("vscode-draft-"+runId)).thenReturn(Optional.of(json.readValue(DOCUMENT, WorkflowDefinition.class)));
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isConflict());
        verifyNoInteractions(creator);
    }

    @Test void snapshotPermissionsProtectEveryReadOfATestRun() throws Exception {
        var restricted = DOCUMENT.replace("\"version\":7", "\"version\":7,\"requiredScopes\":[\"draft:read\"]");
        var secured = mvc(true, true);
        caller.set(new AuthorizationContext("bob", List.of("draft:read"), List.of(TESTER)));
        secured.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(restricted, Map.of())))
            .andExpect(status().isCreated());
        caller.set(new AuthorizationContext("alice", List.of(), List.of(TESTER)));
        for (var suffix : List.of("", "/graph", "/children", "/variables", "/steps", "/logs"))
            secured.perform(get("/workflow/api/processes/" + runId + suffix)).andExpect(status().isForbidden());
    }

    @Test void aGraphWithoutASnapshotIsAConflictRatherThanAServerError() throws Exception {
        store.save(Process.builder().id(runId).workflowDefinitionId("draft").workflowDefinitionVersion(7).name("Draft")
            .status(ProcessStatus.RUNNING).variables(List.of()).build());
        mvc.perform(get("/workflow/api/processes/"+runId+"/graph")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail", containsString("snapshot")));
    }

    @Test void graphUsesTheEffectiveSnapshotIncludesInjectedStepsAndMasksVariables() throws Exception {
        var source = DOCUMENT.replace("\"version\":7", "\"version\":7,\"sensitiveVariables\":[\"token\"]");
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(source, Map.of("token", "synthetic-private-value"))))
            .andExpect(status().isCreated());
        var injected = json.readValue("{\"id\":\"injected\",\"name\":\"Injected\",\"type\":\"ACTION\"}", Step.class);
        when(steps.findByProcessId(runId)).thenReturn(List.of(StepExecution.builder().id("exec-injected").processId(runId)
            .stepId("injected").stepJson(JsonSerializer.toJson(injected)).injectedByStepExecutionId("dynamic-exec")
            .status(StepExecutionStatus.REVIEW_REQUIRED).attemptCount(2).order(3).build()));
        when(definitions.findById("draft")).thenReturn(Optional.of(json.readValue(DOCUMENT.replace("Draft", "New publication"), WorkflowDefinition.class)));
        mvc.perform(get("/workflow/api/processes/" + runId + "/graph"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.definition.name").value("Draft"))
            .andExpect(jsonPath("$.definition.version").value(7)).andExpect(jsonPath("$.testRun").value(true))
            .andExpect(jsonPath("$.definition.steps[2].id").value("injected"))
            .andExpect(jsonPath("$.overlay.injected.state").value("REVIEW_REQUIRED"))
            .andExpect(content().string(not(containsString("synthetic-private-value"))));
    }

    @Test void graphOnlyExposesTopologyAndAgentIdentityRatherThanRequestOrPromptPayloads() throws Exception {
        var raw = DOCUMENT.replace("{\"id\":\"end\",\"type\":\"END\",\"name\":\"End\",\"preconditionStepId\":\"start\"}",
            "{\"id\":\"agent\",\"type\":\"AGENT\",\"name\":\"Agent\",\"agent\":{\"ref\":\"synthetic-agent\",\"taskPrompt\":\"private-prompt\",\"input\":{\"value\":\"private-input\"}}}");
        store.save(Process.builder().id(runId).workflowDefinitionId("draft").workflowDefinitionVersion(7).name("Draft")
            .status(ProcessStatus.RUNNING).workflowDefinitionJson(raw).variables(List.of()).build());
        mvc.perform(get("/workflow/api/processes/"+runId+"/graph")).andExpect(status().isOk())
            .andExpect(jsonPath("$.definition.steps[1].agent.ref").value("synthetic-agent"))
            .andExpect(content().string(not(containsString("private-prompt"))))
            .andExpect(content().string(not(containsString("private-input"))));
    }

    @Test void childrenKeepTheSourceAttemptAndIterationAndArePagedAndAuthorized() throws Exception {
        mvc.perform(post("/workflow/api/test-runs").contentType("application/json").content(request(DOCUMENT, Map.of())))
            .andExpect(status().isCreated());
        when(steps.findByProcessId(runId)).thenReturn(List.of(
            StepExecution.builder().id("old-exec").stepId("sub").processId(runId).iteration(2).itemIndex(3).status(StepExecutionStatus.ERROR).build(),
            StepExecution.builder().id("new-exec").stepId("sub").processId(runId).iteration(3).itemIndex(4).status(StepExecutionStatus.RUNNING).build()));
        for (int i=0;i<3;i++) store.save(Process.builder().id("child-"+i).name("Child").workflowDefinitionId("child")
            .workflowDefinitionVersion(i+1).status(ProcessStatus.RUNNING).parentStepExecutionId(i==0?"old-exec":"new-exec")
            .created(LocalDateTime.of(2026,10,20,9,i)).build());
        mvc.perform(get("/workflow/api/processes/"+runId+"/children").param("size","1").param("stepId","sub"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.content[0].parentStepExecutionId").value("old-exec"))
            .andExpect(jsonPath("$.content[0].iteration").value(2)).andExpect(jsonPath("$.content[0].itemIndex").value(3));
        mvc.perform(get("/workflow/api/processes/"+runId+"/children").param("size","1").param("page","1"))
            .andExpect(jsonPath("$.content[0].id").value("child-1"));
        mvc.perform(get("/workflow/api/processes/"+runId+"/children").param("stepId","other"))
            .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/workflow/api/processes/"+runId+"/children").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/workflow/api/processes/missing/graph")).andExpect(status().isNotFound());
        mvc.perform(get("/workflow/api/processes/missing/children")).andExpect(status().isNotFound());
    }
}
