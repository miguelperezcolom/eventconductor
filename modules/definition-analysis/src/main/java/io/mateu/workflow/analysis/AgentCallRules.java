package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The structural rules of an {@code AGENT_CALL} step and its {@code agent} block, on their generic
 * (parsed JSON/YAML) form — shared, as code, by the engine and the Maven plugin. Templates, output
 * expressions and the JSON Schemas themselves are checked separately, by whoever holds a JEXL engine
 * and a schema validator.
 */
public final class AgentCallRules {

    /** What both Anthropic and OpenAI accept as a tool name. */
    public static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** The step types a {@code step:} tool may be: those that do work. */
    public static final Set<String> TOOL_STEP_TYPES = Set.of("ACTION", "HTTP_CALL", "LLM_CALL", "RULE", "PROCESS", "USER_TASK");
    public static final Set<String> TOOL_KINDS = Set.of("step", "http", "process", "task");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern DURATION = Pattern.compile("P(?:\\d+D)?(?:T(?:\\d+H)?(?:\\d+M)?(?:\\d+(?:\\.\\d+)?S)?)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * Problems with an AGENT_CALL step.
     *
     * @param step      the AGENT_CALL step, as a map
     * @param stepsById every step of the definition, as maps, by id
     */
    public static List<String> problems(Map<?, ?> step, Map<String, ? extends Map<?, ?>> stepsById) {
        var id = String.valueOf(step.get("id"));
        var where = "AGENT_CALL step '" + id + "'";
        var problems = new ArrayList<String>();
        if (truthy(step.get("compensable"))) {
            problems.add(where + " cannot be compensable: its tools carry their own compensation.");
        }
        for (var loop : List.of("forEach", "repeat", "loopBack")) {
            if (step.get(loop) != null) {
                problems.add(where + " cannot declare " + loop + ".");
            }
        }
        if (!(step.get("agent") instanceof Map<?, ?> agent)) {
            problems.add(where + " must define an agent block.");
            return problems;
        }
        if (!set(agent.get("connection"))) {
            problems.add(where + " must name a connection (workflow.llm.connections.<name>).");
        }
        if (!set(agent.get("goal"))) {
            problems.add(where + " must define a goal.");
        }
        positiveInteger(agent.get("maxSteps"), where + " maxSteps", true, problems);
        positiveInteger(agent.get("maxTokens"), where + " maxTokens", false, problems);
        positiveInteger(agent.get("toolConcurrency"), where + " toolConcurrency", false, problems);
        var temperature = agent.get("temperature");
        if (temperature != null) {
            var value = number(temperature);
            if (value == null || value < 0 || value > 2) {
                problems.add(where + " temperature must be a number between 0 and 2.");
            }
        }
        var duration = agent.get("maxDuration");
        if (duration != null && (!DURATION.matcher(String.valueOf(duration).trim()).matches()
                || String.valueOf(duration).trim().equalsIgnoreCase("P") || String.valueOf(duration).trim().equalsIgnoreCase("PT"))) {
            problems.add(where + " maxDuration must be an ISO-8601 duration (PT10M).");
        }
        var onToolError = agent.get("onToolError");
        if (onToolError != null && !Set.of("fail", "report").contains(String.valueOf(onToolError))) {
            problems.add(where + " onToolError must be fail or report.");
        }
        for (var field : List.of("system", "goal", "model")) {
            var value = agent.get(field);
            if (value != null && String.valueOf(value).contains("${secret:")) {
                problems.add(where + ": ${secret:…} is not allowed in an agent block (found in " + field
                        + ") — a secret must never reach a prompt.");
            }
        }
        for (var key : agent.keySet()) {
            if (LlmCallRules.CREDENTIAL_FIELDS.contains(String.valueOf(key))) {
                problems.add(where + " " + key + ": credentials belong to the connection's configuration"
                        + " (workflow.llm.connections.<name>.api-key), never to a definition.");
            }
        }
        checkOutput(agent.get("output"), where, problems);
        checkTools(agent.get("tools"), id, where, stepsById, problems);
        return problems;
    }

    /** The step ids the AGENT_CALL steps of a definition use as tools ({@code step:} tools). */
    public static Set<String> toolStepIds(Collection<? extends Map<?, ?>> steps) {
        var ids = new LinkedHashSet<String>();
        for (var step : steps) {
            if (!"AGENT_CALL".equals(String.valueOf(step.get("type"))) || !(step.get("agent") instanceof Map<?, ?> agent)) {
                continue;
            }
            if (agent.get("tools") instanceof List<?> tools) {
                for (var tool : tools) {
                    if (tool instanceof Map<?, ?> map && set(map.get("step"))) {
                        ids.add(String.valueOf(map.get("step")));
                    }
                }
            }
        }
        return ids;
    }

    private static void checkOutput(Object output, String where, List<String> problems) {
        if (output == null) {
            return;
        }
        if (!(output instanceof Map<?, ?> map)) {
            problems.add(where + " output must be an object with schema, mapping and/or variable.");
            return;
        }
        var schema = map.get("schema");
        if (schema != null && !(schema instanceof Map<?, ?>)) {
            problems.add(where + " output.schema must be a JSON Schema object.");
        }
        var mapping = map.get("mapping");
        if (mapping != null) {
            if (!(mapping instanceof Map<?, ?> m)) {
                problems.add(where + " output.mapping must map variable names to expressions.");
            } else {
                m.keySet().forEach(name -> checkVariable(String.valueOf(name), where + " output.mapping", problems));
            }
        }
        if (set(map.get("variable"))) {
            checkVariable(String.valueOf(map.get("variable")), where + " output.variable", problems);
        }
    }

    private static void checkTools(Object value, String agentId, String where,
                                   Map<String, ? extends Map<?, ?>> stepsById, List<String> problems) {
        if (!(value instanceof List<?> tools) || tools.isEmpty()) {
            problems.add(where + " must declare at least one tool.");
            return;
        }
        var names = new HashSet<String>();
        var reachedByTheFlow = reachedByTheFlow(stepsById.values());
        for (int i = 0; i < tools.size(); i++) {
            var at = where + " tools[" + i + "]";
            if (!(tools.get(i) instanceof Map<?, ?> tool)) {
                problems.add(at + " must be an object.");
                continue;
            }
            var name = tool.get("name") == null ? null : String.valueOf(tool.get("name"));
            if (name == null || !TOOL_NAME.matcher(name).matches()) {
                problems.add(at + " name must match [A-Za-z0-9_-]{1,64}.");
            } else {
                at = where + " tool '" + name + "'";
                if (!names.add(name)) {
                    problems.add(where + " declares the tool '" + name + "' twice.");
                }
            }
            if (!set(tool.get("description"))) {
                problems.add(at + " must have a description: it is all the model knows about it.");
            }
            int kinds = 0;
            for (var kind : TOOL_KINDS) {
                if (tool.get(kind) != null && !(tool.get(kind) instanceof String s && s.isBlank())) {
                    kinds++;
                }
            }
            if (kinds != 1) {
                problems.add(at + " must declare exactly one of step, http, process and task.");
            }
            var schema = tool.get("inputSchema");
            if (schema != null) {
                if (!(schema instanceof Map<?, ?> s)) {
                    problems.add(at + " inputSchema must be a JSON Schema object.");
                } else if (s.get("type") != null && !"object".equals(String.valueOf(s.get("type")))) {
                    problems.add(at + " inputSchema must describe an object (type: object): the arguments are one.");
                }
            }
            if (tool.get("http") != null && !(tool.get("http") instanceof Map<?, ?>)) {
                problems.add(at + " http must be an HTTP_CALL block.");
            }
            if (tool.get("outputVariables") != null && tool.get("process") == null) {
                problems.add(at + " outputVariables applies to a process tool only.");
            }
            if (tool.get("topic") != null && tool.get("task") == null) {
                problems.add(at + " topic applies to a task tool only.");
            }
            var approval = tool.get("approval");
            if (approval != null && (!(approval instanceof Map<?, ?> a) || !set(a.get("formId")))) {
                problems.add(at + " approval must name the formId of the approval form.");
            }
            if (set(tool.get("step"))) {
                checkToolStep(String.valueOf(tool.get("step")), agentId, at, stepsById, reachedByTheFlow, problems);
            }
        }
    }

    private static void checkToolStep(String stepId, String agentId, String at, Map<String, ? extends Map<?, ?>> stepsById,
                                      Set<String> reachedByTheFlow, List<String> problems) {
        var target = stepsById.get(stepId);
        if (target == null) {
            problems.add(at + " references unknown step '" + stepId + "'.");
            return;
        }
        if (stepId.equals(agentId)) {
            problems.add(at + " cannot be the agent step itself.");
            return;
        }
        var type = String.valueOf(target.get("type"));
        if (!TOOL_STEP_TYPES.contains(type)) {
            problems.add(at + ": step '" + stepId + "' is a " + type + "; a tool step must be one of "
                    + new java.util.TreeSet<>(TOOL_STEP_TYPES) + ".");
        }
        if (!preconditionIds(target).isEmpty() || reachedByTheFlow.contains(stepId)) {
            problems.add(at + ": step '" + stepId + "' is part of the normal flow (it has preconditions, or is a"
                    + " precondition, compensation or on-timeout target of another step); a tool step must be"
                    + " used only as a tool.");
        }
        for (var loop : List.of("forEach", "repeat", "loopBack")) {
            if (target.get(loop) != null) {
                problems.add(at + ": step '" + stepId + "' cannot declare " + loop + " — the agent decides how often it runs.");
            }
        }
    }

    /** Every step another step points at: as a precondition, a compensation or an on-timeout route. */
    private static Set<String> reachedByTheFlow(Collection<? extends Map<?, ?>> steps) {
        var reached = new HashSet<String>();
        for (var step : steps) {
            reached.addAll(preconditionIds(step));
            for (var field : List.of("compensationStepId", "onTimeoutStepId")) {
                if (set(step.get(field))) {
                    reached.add(String.valueOf(step.get(field)));
                }
            }
        }
        return reached;
    }

    /** The ids a step waits for, whichever of the three ways it says so. */
    public static Set<String> preconditionIds(Map<?, ?> step) {
        var ids = new LinkedHashSet<String>();
        if (set(step.get("preconditionStepId"))) {
            ids.add(String.valueOf(step.get("preconditionStepId")));
        }
        if (step.get("preconditionStepIds") instanceof List<?> list) {
            list.stream().filter(AgentCallRules::set).map(String::valueOf).forEach(ids::add);
        }
        if (step.get("preconditions") instanceof List<?> list) {
            for (var link : list) {
                if (link instanceof Map<?, ?> map && set(map.get("stepId"))) {
                    ids.add(String.valueOf(map.get("stepId")));
                }
            }
        }
        return ids;
    }

    private static void positiveInteger(Object value, String where, boolean required, List<String> problems) {
        if (value == null) {
            if (required) {
                problems.add(where + " is required: an agent must be bounded.");
            }
            return;
        }
        var number = number(value);
        if (number == null || number < 1 || number != Math.floor(number)) {
            problems.add(where + " must be a positive integer.");
        }
    }

    private static void checkVariable(String name, String where, List<String> problems) {
        if (!VARIABLE.matcher(name).matches()) {
            problems.add(where + " '" + name + "' is not a valid variable name.");
        }
    }

    private static Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean set(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private AgentCallRules() {
    }
}
