package io.mateu.workflow.analysis;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCallRulesTest {

    private static Map<String, Object> agentStep(Map<String, Object> agent) {
        var step = new LinkedHashMap<String, Object>();
        step.put("id", "agent");
        step.put("type", "AGENT_CALL");
        step.put("preconditionStepId", "start");
        step.put("agent", agent);
        return step;
    }

    private static Map<String, Object> agent(Object tools) {
        var agent = new LinkedHashMap<String, Object>();
        agent.put("connection", "claude");
        agent.put("goal", "Help ${customer}");
        agent.put("maxSteps", 5);
        agent.put("tools", tools);
        return agent;
    }

    private static Map<String, Map<?, ?>> steps(Map<String, Object> agentStep, Map<?, ?>... more) {
        var steps = new LinkedHashMap<String, Map<?, ?>>();
        steps.put("start", Map.of("id", "start", "type", "START"));
        steps.put("agent", agentStep);
        for (var step : more) {
            steps.put(String.valueOf(step.get("id")), step);
        }
        return steps;
    }

    @Test
    void aWellFormedAgentHasNoProblems() {
        var step = agentStep(agent(List.of(
                Map.of("name", "lookup", "description", "Look up", "step", "lookup",
                        "inputSchema", Map.of("type", "object")),
                Map.of("name", "fetch", "description", "Fetch", "http", Map.of("method", "GET", "url", "https://x")),
                Map.of("name", "refund", "description", "Refund", "process", "refund-flow",
                        "outputVariables", List.of("refundId"), "approval", Map.of("formId", "approve")),
                Map.of("name", "charge", "description", "Charge", "task", "charge@1", "topic", "payments"))));
        assertThat(AgentCallRules.problems(step, steps(step, Map.of("id", "lookup", "type", "ACTION")))).isEmpty();
        assertThat(AgentCallRules.toolStepIds(steps(step).values())).containsExactly("lookup");
    }

    @Test
    void budgetsBlockAndStepLevelRulesAreChecked() {
        var agent = new LinkedHashMap<String, Object>();
        agent.put("model", "${secret:KEY}");
        agent.put("apiKey", "sk");
        agent.put("maxTokens", 0);
        agent.put("toolConcurrency", 1.5);
        agent.put("temperature", 3);
        agent.put("maxDuration", "ten minutes");
        agent.put("onToolError", "ignore");
        agent.put("output", Map.of("schema", "x", "mapping", Map.of("1bad", "text"), "variable", "ok"));
        var step = agentStep(agent);
        step.put("compensable", true);
        step.put("forEach", Map.of("items", "x"));
        var problems = AgentCallRules.problems(step, steps(step));
        assertThat(problems).anyMatch(p -> p.contains("cannot be compensable"))
                .anyMatch(p -> p.contains("cannot declare forEach"))
                .anyMatch(p -> p.contains("must name a connection"))
                .anyMatch(p -> p.contains("must define a goal"))
                .anyMatch(p -> p.contains("maxSteps is required"))
                .anyMatch(p -> p.contains("maxTokens must be a positive integer"))
                .anyMatch(p -> p.contains("toolConcurrency must be a positive integer"))
                .anyMatch(p -> p.contains("temperature"))
                .anyMatch(p -> p.contains("maxDuration"))
                .anyMatch(p -> p.contains("onToolError"))
                .anyMatch(p -> p.contains("must never reach a prompt"))
                .anyMatch(p -> p.contains("apiKey"))
                .anyMatch(p -> p.contains("output.schema"))
                .anyMatch(p -> p.contains("'1bad'"))
                .anyMatch(p -> p.contains("at least one tool"));
        assertThat(AgentCallRules.problems(Map.of("id", "x", "type", "AGENT_CALL"), Map.of()))
                .containsExactly("AGENT_CALL step 'x' must define an agent block.");
    }

    @Test
    void toolsAreAnAllowlistOfWellFormedReachableOnlyAsToolsSteps() {
        var step = agentStep(agent(List.of(
                Map.of("name", "bad name!", "description", "x", "task", "t@1"),
                Map.of("name", "dup", "description", "x", "task", "t@1"),
                Map.of("name", "dup", "description", "x", "task", "t@1", "step", "lookup"),
                Map.of("name", "nodesc", "process", "p", "topic", "t"),
                Map.of("name", "schema", "description", "x", "task", "t@1", "inputSchema", Map.of("type", "string"),
                        "outputVariables", List.of("a"), "approval", Map.of()),
                Map.of("name", "flow", "description", "x", "step", "used"),
                Map.of("name", "ctl", "description", "x", "step", "end"),
                Map.of("name", "ghost", "description", "x", "step", "ghost"),
                Map.of("name", "self", "description", "x", "step", "agent"),
                Map.of("name", "looping", "description", "x", "step", "looping"),
                Map.of("name", "nohttp", "description", "x", "http", "GET /"),
                "not a tool")));
        var problems = AgentCallRules.problems(step, steps(step,
                Map.of("id", "lookup", "type", "ACTION"),
                Map.of("id", "used", "type", "ACTION", "preconditionStepIds", List.of("start")),
                Map.of("id", "end", "type", "END", "preconditions", List.of(Map.of("stepId", "agent"))),
                Map.of("id", "looping", "type", "ACTION", "repeat", Map.of("until", "x")),
                Map.of("id", "comp", "type", "ACTION", "compensationStepId", "lookup", "onTimeoutStepId", "x")));
        assertThat(problems).anyMatch(p -> p.contains("name must match"))
                .anyMatch(p -> p.contains("declares the tool 'dup' twice"))
                .anyMatch(p -> p.contains("tool 'dup' must declare exactly one of"))
                .anyMatch(p -> p.contains("tool 'nodesc' must have a description"))
                .anyMatch(p -> p.contains("topic applies to a task tool only"))
                .anyMatch(p -> p.contains("must describe an object"))
                .anyMatch(p -> p.contains("outputVariables applies to a process tool only"))
                .anyMatch(p -> p.contains("approval must name the formId"))
                .anyMatch(p -> p.contains("step 'used' is part of the normal flow"))
                .anyMatch(p -> p.contains("step 'lookup' is part of the normal flow"))
                .anyMatch(p -> p.contains("step 'end' is a END"))
                .anyMatch(p -> p.contains("unknown step 'ghost'"))
                .anyMatch(p -> p.contains("cannot be the agent step itself"))
                .anyMatch(p -> p.contains("cannot declare repeat"))
                .anyMatch(p -> p.contains("http must be an HTTP_CALL block"))
                .anyMatch(p -> p.contains("tools[11] must be an object"));
    }
}
