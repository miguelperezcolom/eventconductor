package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.analysis.RemovedStepTypes;
import io.mateu.workflow.application.services.WorkflowDefinitionValidator;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.dtos.events.integration.TaskLogEmitted;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENT_CALL was removed in 2.34.0: a definition using it fails to load with what to use instead, and a
 * process persisted by an older engine — its frozen definition and step JSON still naming the type, an
 * agent possibly mid-loop — still loads, and the step fails, final, with {@code STEP_TYPE_REMOVED}.
 */
class RemovedStepTypeTest {

    private static final String MESSAGE = "AGENT_CALL was removed in 2.34.0; use the AGENT step (agents from the IA"
            + " catalogue) — see " + RemovedStepTypes.AGENT_GUIDE;

    /** A 2.33 definition with an AGENT_CALL, as the engine froze it: its {@code agent} block included. */
    private static final String SUPPORT = """
            {"id":"support","name":"Support","version":1,"steps":[
              {"id":"start","type":"START","name":"Start"},
              {"id":"triage","type":"AGENT_CALL","name":"Triage","preconditionStepId":"start","timeout":60000,
               "agent":{"connection":"claude","goal":"Help ${customerId}","maxSteps":3,
                        "tools":[{"name":"lookup","description":"Look up","step":"lookup"}]}},
              {"id":"end","type":"END","name":"End","preconditionStepId":"triage"}]}""";

    private final WorkflowOrchestrationService service = new WorkflowOrchestrationService();
    private final List<StepExecution> store = new ArrayList<>();
    private Process process;

    @Test
    void theRemovedTypeReadsBackAsRemoved() {
        assertThat(StepType.fromJson("AGENT_CALL")).isEqualTo(StepType.REMOVED);
        assertThat(StepType.fromJson("MESSAGE")).isEqualTo(StepType.WAIT_FOR_MESSAGE);
        assertThat(StepType.fromJson("LLM_CALL")).isEqualTo(StepType.LLM_CALL);
        var step = JsonSerializer.pojoFromJson("""
                {"id":"triage","type":"AGENT_CALL","name":"Triage","agent":{"connection":"claude","maxSteps":3}}""",
                Step.class);
        assertThat(step.type()).isEqualTo(StepType.REMOVED);
    }

    @Test
    void aDefinitionUsingItFailsToLoadWithWhatToUseInstead() {
        var definition = JsonSerializer.pojoFromJson(SUPPORT, WorkflowDefinition.class);
        assertThatThrownBy(definition::checkInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Step 'triage': " + MESSAGE);
        assertThatThrownBy(() -> new WorkflowDefinitionValidator().validate(definition))
                .hasMessageContaining(MESSAGE);
    }

    @Test
    void theFileFailsToLoadWithWhatToUseInsteadNotWithASchemaError() throws Exception {
        var document = new ObjectMapper().readTree(SUPPORT);
        assertThatThrownBy(() -> new WorkflowDefinitionValidator().validateSource(document, "support.json"))
                .isInstanceOf(WorkflowDefinitionValidator.WorkflowDefinitionValidationException.class)
                .hasMessage("Workflow definition file 'support.json' is invalid:\nStep 'triage': " + MESSAGE);
    }

    @Test
    void aProcessReachingItFailsTheStepFinalWithStepTypeRemoved() {
        persistedProcess();
        stepOver();

        var triage = current("triage");
        assertThat(triage.getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(triage.getDeadlineAt()).isNull();
        assertThat(errorLog(triage)).contains(RemovedStepTypes.ERROR_CODE + ": step triage cannot run on this engine: " + MESSAGE);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
        assertThat(current("end").getStatus()).isEqualTo(StepExecutionStatus.CREATED);
    }

    @Test
    void anAgentMidLoopFailsAndItsOpenChildrenAreCancelled() {
        persistedProcess();
        current("start").updateStatus(StepExecutionStatus.COMPLETED);
        // As 2.33 left it: the agent PENDING with its scope, a model turn at worker-llm, a tool call done.
        var agent = current("triage");
        var stepJson = """
                {"id":"triage","type":"AGENT_CALL","name":"Triage","agent":{"connection":"claude","maxSteps":3}}""";
        store.remove(agent);
        agent = agent.toBuilder().stepJson(stepJson).status(StepExecutionStatus.PENDING)
                .scopeJson("{\"__agentKind\":\"agent\",\"goal\":\"Help\"}").build();
        store.add(agent);
        var turn = StepExecution.builder().id("turn-2").processId("p").workflowDefinitionId("support").stepId("triage")
                .stepJson(stepJson).variables(List.of()).status(StepExecutionStatus.PENDING)
                .parentExecutionId(agent.getId()).itemIndex(2).scopeJson("{\"__agentKind\":\"turn\"}").build();
        var tool = StepExecution.builder().id("tool-1").processId("p").workflowDefinitionId("support").stepId("lookup")
                .stepJson("{\"id\":\"lookup\",\"type\":\"ACTION\",\"name\":\"Lookup\"}").variables(List.of())
                .status(StepExecutionStatus.COMPLETED).parentExecutionId(agent.getId()).itemIndex(1).scopeJson("{\"__agentKind\":\"tool\"}").build();
        store.add(turn);
        store.add(tool);
        assertThat(agent.isContainer()).isTrue();

        stepOver();

        assertThat(current("triage").getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(errorLog(current("triage"))).anySatisfy(line -> assertThat(line).startsWith(RemovedStepTypes.ERROR_CODE + ": "));
        assertThat(byId("turn-2").getStatus()).isEqualTo(StepExecutionStatus.CANCELLED);
        assertThat(byId("tool-1").getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    private void persistedProcess() {
        var definition = JsonSerializer.pojoFromJson(SUPPORT, WorkflowDefinition.class);
        process = Process.builder().id("p").status(ProcessStatus.RUNNING).businessKey("bk")
                .workflowDefinitionJson(SUPPORT).variables(new ArrayList<>()).build();
        int order = 1;
        for (Step step : definition.steps()) {
            store.add(StepExecution.create(step, "p", order++));
        }
    }

    private void stepOver() {
        WorkflowOrchestrationService.TransitionResult result;
        int guard = 0;
        do {
            result = service.calculateNextTransitions(process, List.copyOf(store));
            process = result.getUpdatedProcess();
            for (var se : result.getStepsToSave()) {
                store.removeIf(e -> e.getId().equals(se.getId()));
                store.add(se);
            }
        } while (!result.getStepsToSave().isEmpty() && guard++ < 20);
    }

    private StepExecution current(String stepId) {
        return StepExecutions.currentOf(stepId, store);
    }

    private StepExecution byId(String id) {
        return store.stream().filter(e -> id.equals(e.getId())).findFirst().orElseThrow();
    }

    private static List<String> errorLog(StepExecution execution) {
        return execution.popEvents().stream()
                .filter(TaskLogEmitted.class::isInstance).map(TaskLogEmitted.class::cast)
                .map(TaskLogEmitted::message).toList();
    }
}
