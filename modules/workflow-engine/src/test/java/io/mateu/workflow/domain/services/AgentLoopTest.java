package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.core.infra.JsonSerializer;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.time.EngineClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent loop as a state machine, driven step-over by step-over over an in-memory list of
 * executions — every decision made from those executions alone.
 */
class AgentLoopTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DEFINITION = """
            {"id": "agent-def", "name": "Agent", "version": 1, "steps": [
              {"id": "start", "type": "START", "name": "Start"},
              {"id": "agent", "type": "AGENT_CALL", "name": "Agent", "preconditionStepId": "start", "timeout": "PT30S",
               "agent": {"connection": "claude", "system": "Be brief, ${name}.", "goal": "Help ${name}",
                 "tools": [
                   {"name": "lookup", "description": "Look up", "step": "lookup",
                    "inputSchema": {"type": "object", "required": ["id"], "properties": {"id": {"type": "string"}}}},
                   {"name": "fetch", "description": "Fetch", "http": {"method": "GET", "url": "https://shop.example/o/${id}"}},
                   {"name": "charge", "description": "Charge", "task": "charge@1", "topic": "payments",
                    "approval": {"formId": "approve", "candidateGroups": ["leads"]}},
                   {"name": "refund", "description": "Refund", "process": "refund-flow", "outputVariables": ["refundId"]}
                 ],
                 "output": {"mapping": {"answer": "text", "calls": "toolCalls"}},
                 "maxSteps": 4, "maxDuration": "PT1H"}},
              {"id": "end", "type": "END", "name": "End", "preconditionStepId": "agent"},
              {"id": "lookup", "type": "ACTION", "name": "Lookup", "retries": 2}
            ]}
            """;

    private final WorkflowOrchestrationService service = new WorkflowOrchestrationService();
    private final List<StepExecution> store = new ArrayList<>();
    private Process process;

    @AfterEach
    void resetClock() {
        EngineClock.reset();
    }

    private void definition(String json, Variable... variables) {
        var definition = JsonSerializer.pojoFromJson(json, WorkflowDefinition.class);
        definition.checkInvariants();
        process = Process.builder().id("p").status(ProcessStatus.RUNNING).businessKey("bk")
                .workflowDefinitionJson(json).variables(new ArrayList<>(List.of(variables))).build();
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
        } while (!result.getStepsToSave().isEmpty() && guard++ < 30);
    }

    private StepExecution agent() {
        return StepExecutions.currentOf("agent", store);
    }

    private List<StepExecution> children() {
        return AgentLoop.childrenOf(agent(), store);
    }

    private StepExecution lastTurn() {
        return children().stream().filter(c -> AgentLoop.KIND_TURN.equals(c.scope().get(AgentLoop.KIND)))
                .max(Comparator.comparing(StepExecution::getItemIndex)).orElseThrow();
    }

    private void answer(StepExecution turn, String text, Map<String, Object>... calls) {
        var answer = new LinkedHashMap<String, Object>();
        answer.put("text", text);
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < calls.length; i++) {
            var call = new LinkedHashMap<String, Object>(calls[i]);
            call.putIfAbsent("id", "c" + turn.getItemIndex() + "_" + i);
            list.add(call);
        }
        answer.put("toolCalls", list);
        answer.put("usage", Map.of("inputTokens", 10, "outputTokens", 5));
        report(turn, StepExecutionStatus.COMPLETED, new Variable(AgentLoop.ANSWER_VARIABLE, MultiInstance.toJson(answer)));
    }

    private void report(StepExecution execution, StepExecutionStatus status, Variable... variables) {
        execution.recordOutput(List.of(variables));
        execution.updateStatus(status, status == StepExecutionStatus.ERROR);
        stepOver();
    }

    private static Map<String, Object> call(String name, String arguments) {
        return Map.of("name", name, "arguments", arguments);
    }

    private String variable(String name) {
        return process.getVariables().stream().filter(v -> name.equals(v.name())).map(Variable::value).findFirst().orElse(null);
    }

    @Test
    void aTurnAToolCallAndTheAnswerAreChildExecutionsAndTheConversationIsRebuiltFromThem() throws Exception {
        definition(DEFINITION, new Variable("name", "Ann"));
        stepOver();

        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(agent().isAgent()).isTrue();
        assertThat(agent().scope()).containsEntry("goal", "Help Ann").containsEntry("system", "Be brief, Ann.");
        var first = lastTurn();
        assertThat(first.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(first.topic()).isEqualTo("llm-calls");
        var request = JSON.readTree(first.scope().get(AgentLoop.REQUEST_VARIABLE));
        assertThat(request.path("tools")).hasSize(4);
        assertThat(request.path("messages")).hasSize(1);

        answer(first, "Let me look.", call("lookup", "{\"id\":\"O-1\"}"));
        var tool = children().stream().filter(c -> "lookup".equals(c.getStepId())).findFirst().orElseThrow();
        assertThat(tool.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(tool.getVariables()).anyMatch(v -> v.name().equals("id") && v.value().equals("O-1"));

        report(tool, StepExecutionStatus.COMPLETED, new Variable("status", "SHIPPED"));
        var second = lastTurn();
        assertThat(second).isNotEqualTo(first);
        var secondRequest = JSON.readTree(second.scope().get(AgentLoop.REQUEST_VARIABLE));
        assertThat(secondRequest.path("messages")).hasSize(3);
        assertThat(secondRequest.path("messages").get(1).path("toolCalls").get(0).path("name").asText()).isEqualTo("lookup");
        assertThat(secondRequest.path("messages").get(2).path("results").get(0).path("content").asText())
                .isEqualTo("{\"status\":\"SHIPPED\"}");
        assertThat(agent().getOutputJson()).contains("\"state\":\"running\"");

        answer(second, "It shipped.");
        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
        assertThat(variable("answer")).isEqualTo("It shipped.");
        assertThat(variable("calls")).isEqualTo("1");
        assertThat(variable("status")).isNull();
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        var transcript = JSON.readTree(agent().getOutputJson());
        assertThat(transcript.path("state").asText()).isEqualTo("answered");
        assertThat(transcript.path("inputTokens").asLong()).isEqualTo(20);
        assertThat(transcript.path("transcript")).hasSize(4);
    }

    @Test
    void httpProcessAndTaskToolsRunAsTheStepsTheyAreAndAnApprovalComesFirst() throws Exception {
        definition(DEFINITION, new Variable("name", "Bo"));
        stepOver();
        answer(lastTurn(), "", call("fetch", "{\"id\":\"9\"}"), call("refund", "{}"), call("charge", "{\"amount\":5}"));

        var fetch = children().stream().filter(c -> "agent.fetch".equals(c.getStepId())).findFirst().orElseThrow();
        var fetchStep = JsonSerializer.pojoFromJson(fetch.getStepJson(), Step.class);
        assertThat(fetchStep.type().name()).isEqualTo("HTTP_CALL");
        assertThat(fetchStep.http().output()).containsEntry("body", "body");
        assertThat(fetch.getVariables()).anyMatch(v -> v.name().equals("__http") && v.value().contains("https://shop.example/o/9"));
        assertThat(fetch.topic()).isEqualTo("http-calls");
        assertThat(fetchStep.timeout()).isEqualTo(30_000);

        var refund = children().stream().filter(c -> "agent.refund".equals(c.getStepId())).findFirst().orElseThrow();
        assertThat(JsonSerializer.pojoFromJson(refund.getStepJson(), Step.class).childWorkflowDefinitionId()).isEqualTo("refund-flow");
        // toolConcurrency 1: the child process waits for its turn.
        assertThat(refund.getStatus()).isEqualTo(StepExecutionStatus.CREATED);

        var approval = children().stream().filter(c -> "agent.charge.approval".equals(c.getStepId())).findFirst().orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        assertThat(approval.getVariables()).anyMatch(v -> v.name().equals("formId") && v.value().equals("approve"));
        assertThat(children()).noneMatch(c -> "agent.charge".equals(c.getStepId()));

        report(approval, StepExecutionStatus.COMPLETED, new Variable("approved", "true"));
        report(fetch, StepExecutionStatus.COMPLETED, new Variable("status", "200"));
        assertThat(refund.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        report(refund, StepExecutionStatus.COMPLETED, new Variable("refundId", "R-1"));
        var charge = children().stream().filter(c -> "agent.charge".equals(c.getStepId())).findFirst().orElseThrow();
        var chargeStep = JsonSerializer.pojoFromJson(charge.getStepJson(), Step.class);
        assertThat(chargeStep.task()).isEqualTo("charge@1");
        assertThat(charge.topic()).isEqualTo("payments");
        report(charge, StepExecutionStatus.COMPLETED, new Variable("chargeId", "CH"));

        var results = JSON.readTree(lastTurn().scope().get(AgentLoop.REQUEST_VARIABLE)).path("messages").get(2).path("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(2).path("content").asText()).isEqualTo("{\"chargeId\":\"CH\"}");
    }

    @Test
    void theWallClockBudgetEndsTheAgentInTimeout() {
        definition(DEFINITION, new Variable("name", "C"));
        stepOver();
        var turn = lastTurn();
        EngineClock.set(Clock.offset(EngineClock.get(), Duration.ofHours(2)));
        answer(turn, "", call("lookup", "{\"id\":\"1\"}"));

        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.TIMEOUT);
        assertThat(agent().getOutputJson()).contains("failed: AGENT_TIMEOUT");
        assertThat(process.getStatus()).isEqualTo(ProcessStatus.ERROR);
    }

    @Test
    void aMissingToolStepAndAFailedProcessStopTheAgent() {
        definition(DEFINITION, new Variable("name", "D"));
        stepOver();
        store.removeIf(e -> "lookup".equals(e.getStepId()));
        answer(lastTurn(), "", call("lookup", "{\"id\":\"1\"}"));
        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.ERROR);
        assertThat(agent().getOutputJson()).contains("AGENT_TOOL_FAILED");
    }

    @Test
    void anOperatorRetryOfAFailedAgentReplacesItsFailedChildrenAndCarriesOn() {
        definition(DEFINITION, new Variable("name", "E"));
        stepOver();
        answer(lastTurn(), "", call("lookup", "{\"id\":\"1\"}"));
        var tool = children().stream().filter(c -> "lookup".equals(c.getStepId())).findFirst().orElseThrow();
        report(tool, StepExecutionStatus.ERROR);
        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.ERROR);

        // An operator retries it: the agent goes back to CREATED, the step-over starts it again.
        process = process.withStatus(ProcessStatus.RUNNING);
        agent().updateStatus(StepExecutionStatus.CREATED);
        stepOver();
        assertThat(tool.isSuperseded()).isTrue();
        var again = children().stream().filter(c -> "lookup".equals(c.getStepId())).findFirst().orElseThrow();
        assertThat(again.getId()).isNotEqualTo(tool.getId());
        assertThat(again.getStatus()).isEqualTo(StepExecutionStatus.PENDING);
        report(again, StepExecutionStatus.COMPLETED, new Variable("ok", "yes"));
        answer(lastTurn(), "done");
        assertThat(agent().getStatus()).isEqualTo(StepExecutionStatus.COMPLETED);
    }

    @Test
    void definitionErrorsNeedingTheEngineAreReported() {
        var bad = DEFINITION.replace("\"goal\": \"Help ${name}\"", "\"goal\": \"Help ${name +}\"")
                .replace("{\"type\": \"object\", \"required\": [\"id\"], \"properties\": {\"id\": {\"type\": \"string\"}}}",
                        "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": 5}}}")
                .replace("\"url\": \"https://shop.example/o/${id}\"", "\"url\": \"https://shop.example/o/${id(}\"")
                .replace("\"answer\": \"text\"", "\"answer\": \"text.(\"");
        assertThatThrownBy(() -> JsonSerializer.pojoFromJson(bad, WorkflowDefinition.class).checkInvariants())
                .hasMessageContaining("agent.goal")
                .hasMessageContaining("inputSchema is not a valid JSON Schema")
                .hasMessageContaining("tool 'fetch'")
                .hasMessageContaining("output.mapping.answer");
        var unbounded = DEFINITION.replace("\"maxSteps\": 4,", "");
        assertThatThrownBy(() -> JsonSerializer.pojoFromJson(unbounded, WorkflowDefinition.class).checkInvariants())
                .hasMessageContaining("maxSteps is required");
    }

    @Test
    void parsingRedactingAndCapping() {
        var turn = AgentLoop.parseTurn(3, "not json");
        assertThat(turn.text()).isEqualTo("not json");
        assertThat(turn.calls()).isEmpty();
        var withCalls = AgentLoop.parseTurn(1, "{\"text\":\"t\",\"toolCalls\":[{\"name\":\"x\",\"arguments\":{\"a\":1}}],"
                + "\"usage\":{\"inputTokens\":\"7\",\"outputTokens\":2}}");
        assertThat(withCalls.calls()).containsExactly(new AgentLoop.Call("call_1_0", "x", "{\"a\":1}"));
        assertThat(withCalls.totalTokens()).isEqualTo(9);
        assertThat(AgentLoop.parseTurn(0, null).text()).isEmpty();

        assertThat(AgentLoop.redact("{\"user\":\"ann\",\"password\":\"p\",\"nested\":[{\"apiKey\":\"k\",\"x\":\"{\\\"token\\\":\\\"t\\\"}\"}]}"))
                .isEqualTo("{\"user\":\"ann\",\"password\":\"***\",\"nested\":[{\"apiKey\":\"***\",\"x\":\"{\\\"token\\\":\\\"***\\\"}\"}]}");
        assertThat(AgentLoop.redact("plain text password")).isEqualTo("plain text password");
        assertThat(AgentLoop.redact("{broken")).isEqualTo("{broken");
        assertThat(AgentLoop.redact(null)).isNull();
        assertThat(AgentLoop.cap("abcdef", 3)).isEqualTo("abc… [3 more characters]");
        assertThat(AgentLoop.cap("ab", 3)).isEqualTo("ab");
    }

    @Test
    void aLongTranscriptElidesItsOldestEntries() throws Exception {
        definition(DEFINITION, new Variable("name", "F"));
        stepOver();
        var big = "x".repeat(1_900);
        for (int i = 0; i < 4; i++) {
            answer(lastTurn(), big + i, call("lookup", "{\"id\":\"" + i + "\"}"));
            var tool = children().stream().filter(c -> "lookup".equals(c.getStepId()) && !c.getStatus().isTerminal())
                    .findFirst().orElseThrow();
            report(tool, StepExecutionStatus.COMPLETED, new Variable("blob", big));
        }
        var history = new ArrayList<AgentLoop.Turn>();
        for (int i = 0; i < 40; i++) {
            history.add(new AgentLoop.Turn(i, big, null, List.of(), 1, 1, null, null));
        }
        var step = JsonSerializer.pojoFromJson(agent().getStepJson(), Step.class);
        var transcript = JSON.readTree(AgentLoop.transcript(agent(), step, children(), history, "running"));
        assertThat(transcript.path("elided").asInt()).isPositive();
        assertThat(transcript.toString().length()).isLessThanOrEqualTo(AgentLoop.MAX_TRANSCRIPT_CHARS + 100);
        assertThat(transcript.path("transcript").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void theTranscriptKeepsTheProcesssSensitiveValuesOut() throws Exception {
        definition(DEFINITION.replace("\"version\": 1,", "\"version\": 1, \"sensitiveVariables\": [\"name\"],")
                        .replace("\"maxSteps\": 4,", "\"maxSteps\": 4, \"allowSensitiveVariables\": true,"),
                new Variable("name", "Ferdinand"));
        stepOver();
        answer(lastTurn(), "Looking Ferdinand up", call("lookup", "{\"id\":\"1\",\"name\":\"Ferdinand\"}"));
        var step = JsonSerializer.pojoFromJson(agent().getStepJson(), Step.class);
        var history = List.of(new AgentLoop.Turn(0, "Looking Ferdinand up", null,
                List.of(new AgentLoop.Call("c1", "lookup", "{\"id\":\"1\",\"name\":\"Ferdinand\"}")), 1, 1, null, null));

        var shown = AgentLoop.transcript(agent(), step, children(), history, "running", process);
        assertThat(shown).doesNotContain("Ferdinand").contains("••••").contains("lookup");
        // Without the process (nothing known to be sensitive) it is as before.
        assertThat(AgentLoop.transcript(agent(), step, children(), history, "running")).contains("Ferdinand");
    }
}
