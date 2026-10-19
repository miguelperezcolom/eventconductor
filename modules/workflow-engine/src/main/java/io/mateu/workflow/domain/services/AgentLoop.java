package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.AgentCall;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.StepExecutionStatus;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.time.EngineClock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static io.mateu.core.infra.JsonSerializer.pojoFromJson;
import static io.mateu.core.infra.JsonSerializer.toJson;

/**
 * The loop of an {@code AGENT_CALL} step: model turn, tool calls, results, next turn — until the model
 * answers or a budget is spent.
 *
 * <p>The agent's execution is a container (the forEach model): its children are step executions — one
 * per model turn (dispatched to the built-in {@code agent-turn} task) and one per tool call (the tool's
 * own step, with its own timeout, retries, topic and compensation), plus a USER_TASK per call of a tool
 * that needs approval. Nothing is kept in memory: every decision here is a pure function of the
 * persisted children, so a crash anywhere resumes on the next step-over with the same decision, and a
 * redelivered step-over makes it again without effect.
 *
 * <p>What a child is lives in its scope: {@link #KIND} ({@code turn}, {@code tool}, {@code approval}),
 * {@link #TURN} (which model turn it belongs to), and for a call {@link #CALL_ID}, {@link #TOOL} and
 * {@link #ARGUMENTS} — next to the arguments themselves, laid over the process variables for that
 * execution only.
 */
public final class AgentLoop {

    /** The taskId the built-in worker serves for one model turn. */
    public static final String TASK_ID = "agent-turn@1";
    /** The task variable holding a turn's request: the conversation so far and the tool specs. */
    public static final String REQUEST_VARIABLE = "__agent";
    /** The variable a turn's worker reports its answer in. */
    public static final String ANSWER_VARIABLE = "__agentAnswer";

    public static final String KIND = "__agentKind";
    public static final String TURN = "__agentTurn";
    public static final String CALL_ID = "__agentCallId";
    public static final String TOOL = "__agentTool";
    public static final String ARGUMENTS = "__agentArguments";

    public static final String KIND_AGENT = "agent";
    public static final String KIND_TURN = "turn";
    public static final String KIND_TOOL = "tool";
    public static final String KIND_APPROVAL = "approval";

    /** What the model sees of one tool result, at most. */
    public static final int MAX_RESULT_CHARS = 16_000;
    /** One transcript entry's text, at most. */
    public static final int MAX_TRANSCRIPT_ENTRY_CHARS = 2_000;
    /** The whole persisted transcript, at most. */
    public static final int MAX_TRANSCRIPT_CHARS = 64_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[-_]?key|authorization|credential|cookie).*");

    /** What the orchestrator's pass offers this loop: the process, the executions, and how to record changes. */
    public interface Pass {
        Process process();

        void process(Process process);

        List<StepExecution> working();

        void save(StepExecution execution);

        void create(StepExecution execution);

        boolean createdNow(String executionId);
    }

    // ------------------------------------------------------------------ start

    /**
     * Starts the agent: renders {@code system} and {@code goal} once, from the process as it stands,
     * into the container's scope — the conversation's first message never changes after that — and
     * materialises the first turn. A container that already has children is being run again (an
     * operator retried it): what completed stands, and each failed or cancelled child is replaced.
     */
    public void start(StepExecution container, Step step, Pass pass) {
        var existing = childrenOf(container, pass.working());
        if (!existing.isEmpty()) {
            for (var child : existing) {
                var status = child.getStatus();
                if (status == StepExecutionStatus.ERROR || status == StepExecutionStatus.TIMEOUT
                        || status == StepExecutionStatus.CANCELLED) {
                    child.supersede(null);
                    pass.save(child);
                    pass.create(StepExecution.agentChildOf(container, child.getStepId(), child.getStepJson(),
                            nextIndex(container, pass.working()), child.scope()));
                }
            }
            container.beginAgent(pass.process(), container.scope());
            pass.save(container);
            return;
        }
        var agent = step.agent();
        var scope = new LinkedHashMap<String, String>();
        scope.put(KIND, KIND_AGENT);
        try {
            var context = TemplateContext.of(pass.process(), step, container.getId(), null);
            scope.put("system", agent.system() == null ? null : Templates.renderText(agent.system(), context));
            scope.put("goal", Templates.renderText(agent.goal(), context));
        } catch (RuntimeException e) {
            container.beginAgent(pass.process(), scope);
            fail(container, StepExecutionStatus.ERROR, "AGENT_REQUEST",
                    "the system prompt or goal could not be rendered: " + e.getMessage(), List.of(), pass);
            return;
        }
        container.beginAgent(pass.process(), scope);
        pass.save(container);
        createTurn(container, step, 0, List.of(), pass);
    }

    // ------------------------------------------------------------------ advance

    /** Moves the agent on: see the class comment. */
    public void advance(StepExecution container, Step step, Pass pass, boolean processFailed) {
        var agent = step.agent();
        var children = childrenOf(container, pass.working());
        if (processFailed) {
            cancel(children, pass);
            container.updateStatus(StepExecutionStatus.CANCELLED);
            container.logInfo("Agent " + container.getStepId() + " stopped: the process failed.");
            pass.save(container);
            return;
        }
        var turns = children.stream().filter(child -> KIND_TURN.equals(child.scope().get(KIND))).toList();
        if (turns.isEmpty()) {
            // Started by a path that does not know agents (or a pass that saw it half-saved): begin.
            if (container.scope().get("goal") == null) {
                start(container, step, pass);
            } else {
                createTurn(container, step, 0, children, pass);
            }
            return;
        }
        var budget = agent.duration();
        if (budget != null && container.getStartedAt() != null
                && !EngineClock.now().isBefore(container.getStartedAt().plus(budget))) {
            fail(container, StepExecutionStatus.TIMEOUT, "AGENT_TIMEOUT", "the agent ran past its maxDuration "
                    + agent.maxDuration() + ".", children, pass);
            return;
        }
        var last = turns.getLast();
        var status = last.getStatus();
        if (status == StepExecutionStatus.CREATED) {
            if (!pass.createdNow(last.getId())) {
                last.start(pass.process());
                pass.save(last);
            }
            return;
        }
        if (status.keepsTheProcessInFlight()) {
            return;
        }
        if (status != StepExecutionStatus.COMPLETED) {
            fail(container, status == StepExecutionStatus.TIMEOUT ? StepExecutionStatus.TIMEOUT : StepExecutionStatus.ERROR,
                    "AGENT_MODEL_FAILED", "model turn " + turnOf(last) + " ended " + status + ".", children, pass);
            return;
        }
        var history = history(turns, children);
        var tokens = history.stream().mapToLong(Turn::totalTokens).sum();
        if (agent.maxTokens() != null && tokens > agent.maxTokens()) {
            fail(container, StepExecutionStatus.ERROR, "AGENT_MAX_TOKENS", "the agent used " + tokens
                    + " tokens, over its maxTokens " + agent.maxTokens() + ".", children, pass);
            return;
        }
        var turn = history.getLast();
        if (turn.calls().isEmpty()) {
            finish(container, step, history, pass);
            return;
        }
        int callsBefore = history.subList(0, history.size() - 1).stream().mapToInt(t -> t.calls().size()).sum();
        if (callsBefore + turn.calls().size() > agent.stepBudget()) {
            fail(container, StepExecutionStatus.ERROR, "AGENT_MAX_STEPS", "the model asked for "
                    + (callsBefore + turn.calls().size()) + " tool calls, over its maxSteps " + agent.stepBudget() + ".",
                    children, pass);
            return;
        }
        var ofThisTurn = children.stream()
                .filter(child -> !KIND_TURN.equals(child.scope().get(KIND)))
                .filter(child -> String.valueOf(turn.index()).equals(child.scope().get(TURN)))
                .toList();
        boolean allResolved = true;
        int inFlight = (int) ofThisTurn.stream()
                .filter(child -> KIND_TOOL.equals(child.scope().get(KIND)) && child.getStatus().keepsTheProcessInFlight())
                .count();
        var toStart = new ArrayList<StepExecution>();
        for (var call : turn.calls()) {
            var resolution = resolve(call, agent);
            if (resolution.rejection() != null) {
                continue;
            }
            var approval = find(ofThisTurn, KIND_APPROVAL, call.id());
            var tool = find(ofThisTurn, KIND_TOOL, call.id());
            if (resolution.tool().approval() != null && tool == null) {
                if (approval == null) {
                    createApproval(container, step, turn.index(), call, resolution, pass);
                    allResolved = false;
                    continue;
                }
                if (approval.getStatus() == StepExecutionStatus.CREATED) {
                    toStart.add(approval);
                    allResolved = false;
                    continue;
                }
                if (approval.getStatus().keepsTheProcessInFlight()) {
                    allResolved = false;
                    continue;
                }
                if (approval.getStatus() != StepExecutionStatus.COMPLETED) {
                    if (!agent.reportsToolErrors()) {
                        fail(container, StepExecutionStatus.ERROR, "AGENT_TOOL_FAILED", "the approval of call "
                                + call.id() + " (" + call.name() + ") ended " + approval.getStatus() + ".", children, pass);
                        return;
                    }
                    continue;
                }
                if (!approved(approval)) {
                    continue;
                }
                var created = createTool(container, step, turn.index(), call, resolution, pass);
                if (created == null) {
                    fail(container, StepExecutionStatus.ERROR, "AGENT_TOOL_FAILED", "the tool step '"
                            + resolution.tool().step() + "' of call " + call.id() + " no longer exists.", children, pass);
                    return;
                }
                allResolved = false;
                continue;
            }
            if (tool == null) {
                var created = createTool(container, step, turn.index(), call, resolution, pass);
                if (created == null) {
                    fail(container, StepExecutionStatus.ERROR, "AGENT_TOOL_FAILED", "the tool step '"
                            + resolution.tool().step() + "' of call " + call.id() + " no longer exists.", children, pass);
                    return;
                }
                allResolved = false;
                continue;
            }
            var toolStatus = tool.getStatus();
            if (toolStatus == StepExecutionStatus.COMPLETED) {
                continue;
            }
            if (toolStatus == StepExecutionStatus.ERROR || toolStatus == StepExecutionStatus.TIMEOUT
                    || toolStatus == StepExecutionStatus.CANCELLED) {
                if (!agent.reportsToolErrors()) {
                    fail(container, StepExecutionStatus.ERROR, "AGENT_TOOL_FAILED", "tool '" + call.name()
                            + "' (call " + call.id() + ") ended " + toolStatus + ".", children, pass);
                    return;
                }
                continue;
            }
            allResolved = false;
            if (toolStatus == StepExecutionStatus.CREATED) {
                toStart.add(tool);
            }
        }
        for (var child : toStart) {
            if (pass.createdNow(child.getId())) {
                continue;
            }
            boolean isTool = KIND_TOOL.equals(child.scope().get(KIND));
            if (isTool && inFlight >= agent.concurrency()) {
                continue;
            }
            child.start(pass.process());
            pass.save(child);
            if (isTool) {
                inFlight++;
            }
        }
        recordTranscript(container, step, children, history, pass);
        if (allResolved) {
            createTurn(container, step, turn.index() + 1, children, pass);
        }
    }

    // ------------------------------------------------------------------ the conversation

    /** One tool call the model asked for. */
    public record Call(String id, String name, String arguments) {
    }

    /** One completed model turn: what it said, the calls it asked for, and what it cost. */
    public record Turn(int index, String text, Object json, List<Call> calls, long inputTokens, long outputTokens,
                       String model, String finishReason) {
        long totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    /** The result of one call as the model is told it: a JSON document, and whether it is an error. */
    public record Result(String callId, String name, String content, boolean error, String outcome) {
    }

    private record Resolution(AgentCall.AgentTool tool, Map<String, Object> arguments, String rejection) {
    }

    /** The completed turns, in order, with their answers parsed. */
    static List<Turn> history(List<StepExecution> turns, List<StepExecution> children) {
        var history = new ArrayList<Turn>();
        for (var turn : turns) {
            if (turn.getStatus() != StepExecutionStatus.COMPLETED) {
                continue;
            }
            history.add(parseTurn(turnOf(turn), turn.output().get(ANSWER_VARIABLE)));
        }
        return history;
    }

    /** A turn's answer as the worker reported it. Unreadable reads as an empty final answer. */
    public static Turn parseTurn(int index, String answer) {
        Map<String, Object> map = Map.of();
        try {
            if (answer != null && !answer.isBlank()) {
                map = JSON.readValue(answer, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            }
        } catch (Exception unreadable) {
            map = Map.of("text", answer);
        }
        var calls = new ArrayList<Call>();
        if (map.get("toolCalls") instanceof List<?> list) {
            int i = 0;
            for (var item : list) {
                if (item instanceof Map<?, ?> call) {
                    var id = call.get("id") == null ? "call_" + index + "_" + i : String.valueOf(call.get("id"));
                    var arguments = call.get("arguments");
                    String args = arguments == null ? "{}" : arguments instanceof String s ? s : MultiInstance.toJson(arguments);
                    calls.add(new Call(id, call.get("name") == null ? null : String.valueOf(call.get("name")), args));
                }
                i++;
            }
        }
        var usage = map.get("usage") instanceof Map<?, ?> u ? u : Map.of();
        return new Turn(index, map.get("text") == null ? "" : String.valueOf(map.get("text")), map.get("json"),
                List.copyOf(calls), number(usage.get("inputTokens")), number(usage.get("outputTokens")),
                map.get("model") == null ? null : String.valueOf(map.get("model")),
                map.get("finishReason") == null ? null : String.valueOf(map.get("finishReason")));
    }

    /**
     * Whether the model may have this call run: a declared tool, and arguments that are a JSON object
     * valid against its {@code inputSchema}. Deterministic — the same call is judged the same on every
     * pass — so a rejection is never stored, it is recomputed.
     */
    private static Resolution resolve(Call call, AgentCall agent) {
        var tool = agent.tool(call.name());
        if (tool == null) {
            return new Resolution(null, null, "there is no tool called '" + call.name() + "'. The tools are: "
                    + agent.toolList().stream().map(AgentCall.AgentTool::name).toList() + ".");
        }
        Map<String, Object> arguments;
        try {
            var parsed = JSON.readValue(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(), Object.class);
            if (!(parsed instanceof Map<?, ?>)) {
                return new Resolution(tool, null, "the arguments must be a JSON object.");
            }
            @SuppressWarnings("unchecked")
            var asMap = (Map<String, Object>) parsed;
            arguments = asMap;
        } catch (Exception e) {
            return new Resolution(tool, null, "the arguments are not valid JSON.");
        }
        if (tool.inputSchema() != null && !tool.inputSchema().isEmpty()) {
            var errors = AgentSchemas.validate(tool.inputSchema(), call.arguments() == null ? "{}" : call.arguments());
            if (!errors.isEmpty()) {
                return new Resolution(tool, arguments, "the arguments do not match the tool's inputSchema — "
                        + String.join("; ", errors));
            }
        }
        return new Resolution(tool, arguments, null);
    }

    /** What the model is told about each call of a turn, in call order; null for a call still running. */
    static List<Result> results(Turn turn, List<StepExecution> children, AgentCall agent) {
        var ofThisTurn = children.stream()
                .filter(child -> !KIND_TURN.equals(child.scope().get(KIND)))
                .filter(child -> String.valueOf(turn.index()).equals(child.scope().get(TURN)))
                .toList();
        var results = new ArrayList<Result>();
        for (var call : turn.calls()) {
            var resolution = resolve(call, agent);
            if (resolution.rejection() != null) {
                results.add(error(call, "The call was not run: " + resolution.rejection(), "rejected"));
                continue;
            }
            var tool = find(ofThisTurn, KIND_TOOL, call.id());
            var approval = find(ofThisTurn, KIND_APPROVAL, call.id());
            if (tool == null && approval != null && approval.getStatus().isTerminal()) {
                if (approval.getStatus() != StepExecutionStatus.COMPLETED) {
                    results.add(error(call, "The approval of this call ended " + approval.getStatus() + "; it was not run.", "failed"));
                    continue;
                }
                if (!approved(approval)) {
                    var comment = approval.output().get("comment");
                    results.add(error(call, "A person rejected this call; it was not run."
                            + (comment == null || comment.isBlank() ? "" : " Their comment: " + comment), "denied"));
                    continue;
                }
            }
            if (tool == null || !tool.getStatus().isTerminal()) {
                results.add(null);
                continue;
            }
            if (tool.getStatus() == StepExecutionStatus.COMPLETED) {
                var reported = new LinkedHashMap<String, Object>();
                tool.output().forEach((name, value) -> reported.put(name, MultiInstance.structured(value)));
                results.add(new Result(call.id(), call.name(), cap(MultiInstance.toJson(reported), MAX_RESULT_CHARS), false, "completed"));
            } else {
                results.add(error(call, "The tool ended " + tool.getStatus() + ".", "failed"));
            }
        }
        return results;
    }

    private static Result error(Call call, String message, String outcome) {
        return new Result(call.id(), call.name(), MultiInstance.toJson(Map.of("error", message)), true, outcome);
    }

    /**
     * The request of turn {@code index}: the connection, the system prompt (with the output schema's
     * instruction), the tools, and the messages — the goal, then each earlier turn's answer and the
     * results of its calls.
     */
    static String request(StepExecution container, Step step, List<Turn> history, List<StepExecution> children) {
        var agent = step.agent();
        var scope = container.scope();
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "user", "content", scope.getOrDefault("goal", "")));
        for (var turn : history) {
            var assistant = new LinkedHashMap<String, Object>();
            assistant.put("role", "assistant");
            assistant.put("content", turn.text());
            if (!turn.calls().isEmpty()) {
                assistant.put("toolCalls", turn.calls().stream().map(call -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("id", call.id());
                    map.put("name", call.name());
                    map.put("arguments", call.arguments());
                    return map;
                }).toList());
            }
            messages.add(assistant);
            if (!turn.calls().isEmpty()) {
                var results = results(turn, children, agent).stream().filter(Objects::nonNull).map(result -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("id", result.callId());
                    map.put("name", result.name());
                    map.put("content", result.content());
                    return map;
                }).toList();
                messages.add(Map.of("role", "tool", "results", results));
            }
        }
        var request = new LinkedHashMap<String, Object>();
        request.put("connection", agent.connection());
        request.put("model", agent.model());
        request.put("system", scope.get("system"));
        request.put("temperature", agent.temperature());
        request.put("responseSchema", agent.output() == null ? null : agent.output().schema());
        request.put("tools", agent.toolList().stream().map(tool -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("name", tool.name());
            map.put("description", tool.description());
            map.put("inputSchema", tool.inputSchema() == null ? Map.of("type", "object") : tool.inputSchema());
            return map;
        }).toList());
        request.put("messages", messages);
        return MultiInstance.toJson(request);
    }

    // ------------------------------------------------------------------ children

    private void createTurn(StepExecution container, Step step, int index, List<StepExecution> children, Pass pass) {
        var turns = children.stream().filter(child -> KIND_TURN.equals(child.scope().get(KIND))).toList();
        var history = history(turns, children);
        var scope = new LinkedHashMap<String, String>();
        scope.put(KIND, KIND_TURN);
        scope.put(TURN, String.valueOf(index));
        scope.put(REQUEST_VARIABLE, request(container, step, history, children));
        pass.create(StepExecution.agentChildOf(container, container.getStepId(), container.getStepJson(),
                nextIndex(container, pass.working()), scope));
        recordTranscript(container, step, children, history, pass);
    }

    private void createApproval(StepExecution container, Step step, int turn, Call call, Resolution resolution, Pass pass) {
        var tool = resolution.tool();
        var approval = new LinkedHashMap<String, Object>();
        approval.put("id", step.id() + "." + tool.name() + ".approval");
        approval.put("workflowDefinitionId", step.workflowDefinitionId());
        approval.put("type", StepType.USER_TASK.name());
        approval.put("name", "Approve " + tool.name());
        approval.put("description", "The agent of step " + step.id() + " wants to call " + tool.name()
                + ". Approve (approved = true) or reject it.");
        approval.put("formId", tool.approval().formId());
        if (tool.approval().assignment() != null) {
            approval.put("assignment", tool.approval().assignment());
        }
        var stepJson = toJson(pojoFromJson(toJson(approval), Step.class));
        pass.create(StepExecution.agentChildOf(container, step.id() + "." + tool.name() + ".approval", stepJson,
                nextIndex(container, pass.working()), callScope(KIND_APPROVAL, turn, call, resolution)));
        container.logInfo("Agent " + step.id() + ": call " + call.id() + " of " + tool.name() + " waits for approval.");
    }

    /** The child that runs one call, created; null when its template step cannot be found. */
    private StepExecution createTool(StepExecution container, Step step, int turn, Call call, Resolution resolution, Pass pass) {
        var tool = resolution.tool();
        String stepId;
        String stepJson;
        if ("step".equals(tool.kind())) {
            var template = StepExecutions.currentOf(tool.step(), pass.working());
            if (template == null) {
                return null;
            }
            stepId = tool.step();
            stepJson = template.getStepJson();
        } else {
            stepId = step.id() + "." + tool.name();
            stepJson = toJson(synthesize(step, tool, stepId));
        }
        var child = StepExecution.agentChildOf(container, stepId, stepJson, nextIndex(container, pass.working()),
                callScope(KIND_TOOL, turn, call, resolution));
        pass.create(child);
        return child;
    }

    /**
     * The step an {@code http}, {@code process} or {@code task} tool runs as: an HTTP_CALL (reporting
     * {@code status} and {@code body} unless it maps its own output), a PROCESS (its
     * {@code outputVariables} are the result) or an ACTION on the task contract — with the agent step's
     * timeout and retries, a PROCESS with no timeout (a child process takes as long as it takes).
     */
    static Step synthesize(Step agentStep, AgentCall.AgentTool tool, String stepId) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", stepId);
        map.put("workflowDefinitionId", agentStep.workflowDefinitionId());
        map.put("name", tool.name());
        map.put("description", tool.description());
        map.put("retries", agentStep.retries());
        switch (tool.kind()) {
            case "http" -> {
                map.put("type", StepType.HTTP_CALL.name());
                map.put("timeout", agentStep.timeout());
                var http = JSON.convertValue(tool.http(), new TypeReference<LinkedHashMap<String, Object>>() {
                });
                http.values().removeIf(Objects::isNull);
                if (!(http.get("output") instanceof Map<?, ?> output) || output.isEmpty()) {
                    http.put("output", Map.of("status", "status", "body", "body"));
                }
                map.put("http", http);
            }
            case "process" -> {
                map.put("type", StepType.PROCESS.name());
                map.put("childWorkflowDefinitionId", tool.process());
                map.put("outputVariables", tool.outputVariables() == null ? List.of() : tool.outputVariables());
            }
            default -> {
                map.put("type", StepType.ACTION.name());
                map.put("timeout", agentStep.timeout());
                map.put("task", tool.task());
                if (tool.topic() != null) {
                    map.put("topic", tool.topic());
                }
            }
        }
        return pojoFromJson(toJson(map), Step.class);
    }

    private static Map<String, String> callScope(String kind, int turn, Call call, Resolution resolution) {
        var scope = new LinkedHashMap<String, String>();
        resolution.arguments().forEach((name, value) -> scope.put(name, MultiInstance.asVariableValue(value)));
        scope.put(KIND, kind);
        scope.put(TURN, String.valueOf(turn));
        scope.put(CALL_ID, call.id());
        scope.put(TOOL, call.name());
        scope.put(ARGUMENTS, call.arguments());
        return scope;
    }

    private static StepExecution find(List<StepExecution> children, String kind, String callId) {
        return children.stream()
                .filter(child -> kind.equals(child.scope().get(KIND)) && callId.equals(child.scope().get(CALL_ID)))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static boolean approved(StepExecution approval) {
        return Truthiness.isTruthy(MultiInstance.structured(approval.output().get("approved")));
    }

    /** The live children of an agent, in the order they were created. */
    public static List<StepExecution> childrenOf(StepExecution container, List<StepExecution> executions) {
        return executions.stream()
                .filter(e -> container.getId().equals(e.getParentExecutionId()) && !e.isSuperseded())
                .sorted(Comparator.comparing(StepExecution::getItemIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static int nextIndex(StepExecution container, List<StepExecution> executions) {
        return executions.stream()
                .filter(e -> container.getId().equals(e.getParentExecutionId()) && e.getItemIndex() != null)
                .mapToInt(StepExecution::getItemIndex)
                .max().orElse(-1) + 1;
    }

    private static int turnOf(StepExecution turn) {
        try {
            return Integer.parseInt(turn.scope().getOrDefault(TURN, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ ending

    private void finish(StepExecution container, Step step, List<Turn> history, Pass pass) {
        var agent = step.agent();
        var answer = history.getLast();
        var output = agent.output();
        var process = pass.process();
        var usage = usage(history);
        try {
            if (output != null && output.variable() != null && !output.variable().isBlank()) {
                process = withVariable(process, output.variable(), answer.json() != null
                        ? MultiInstance.toJson(answer.json()) : answer.text());
            }
            if (output != null && output.mapping() != null) {
                var context = new LinkedHashMap<String, Object>();
                context.put("text", answer.text());
                context.put("json", answer.json() != null ? answer.json() : MultiInstance.structured(answer.text()));
                context.put("usage", usage);
                context.put("turns", history.size());
                context.put("toolCalls", usage.get("toolCalls"));
                for (var entry : output.mapping().entrySet()) {
                    var value = Templates.render("${" + entry.getValue() + "}", context);
                    process = withVariable(process, entry.getKey(), MultiInstance.asVariableValue(value));
                }
            }
        } catch (RuntimeException e) {
            fail(container, StepExecutionStatus.ERROR, "AGENT_OUTPUT", "the final answer could not be mapped: "
                    + e.getMessage(), List.of(), pass);
            return;
        }
        pass.process(process);
        container.completeIterations(transcript(container, step, childrenOf(container, pass.working()), history, "answered", pass.process()),
                "Agent " + step.id() + " answered after " + history.size() + (history.size() == 1 ? " turn" : " turns")
                        + " and " + usage.get("toolCalls") + " tool calls (" + (usage.get("inputTokens")) + " in / "
                        + usage.get("outputTokens") + " out tokens).");
        pass.save(container);
    }

    /** Ends the agent: every child still going is cancelled, and the step ends {@code status}, final. */
    private void fail(StepExecution container, StepExecutionStatus status, String code, String why,
                      List<StepExecution> children, Pass pass) {
        cancel(children, pass);
        try {
            var step = pojoFromJson(container.getStepJson(), Step.class);
            var turns = children.stream().filter(child -> KIND_TURN.equals(child.scope().get(KIND))).toList();
            container.recordAgentState(transcript(container, step, children, history(turns, children), "failed: " + code, pass.process()));
        } catch (RuntimeException unreadable) {
            // the failure below matters more than its transcript
        }
        container.failIterations(status, code + ": agent " + container.getStepId() + " — " + why);
        pass.save(container);
    }

    private static void cancel(List<StepExecution> children, Pass pass) {
        for (var child : children) {
            var status = child.getStatus();
            if (status == StepExecutionStatus.CREATED) {
                child.cancelQuietly();
                pass.save(child);
            } else if (status.keepsTheProcessInFlight()) {
                child.updateStatus(StepExecutionStatus.CANCELLED);
                pass.save(child);
            }
        }
    }

    private static Process withVariable(Process process, String name, String value) {
        var variables = new ArrayList<Variable>();
        process.getVariables().stream().filter(v -> !name.equals(v.name())).forEach(variables::add);
        variables.add(new Variable(name, value));
        return process.withVariables(variables);
    }

    // ------------------------------------------------------------------ transcript

    private void recordTranscript(StepExecution container, Step step, List<StepExecution> children, List<Turn> history,
                                  Pass pass) {
        var json = transcript(container, step, children, history, "running", pass.process());
        if (!json.equals(container.getOutputJson())) {
            container.recordAgentState(json);
            pass.save(container);
        }
    }

    static Map<String, Object> usage(List<Turn> history) {
        var usage = new LinkedHashMap<String, Object>();
        usage.put("inputTokens", history.stream().mapToLong(Turn::inputTokens).sum());
        usage.put("outputTokens", history.stream().mapToLong(Turn::outputTokens).sum());
        usage.put("totalTokens", history.stream().mapToLong(Turn::totalTokens).sum());
        usage.put("turns", history.size());
        usage.put("toolCalls", history.stream().mapToInt(turn -> turn.calls().size()).sum());
        return usage;
    }

    /**
     * The transcript kept on the agent's execution: the goal, each turn's message and calls, each
     * call's arguments and result — redacted (values under secret-looking keys become {@code ***}) and
     * capped (each entry, and the whole, oldest turns elided first) — with the usage so far.
     */
    public static String transcript(StepExecution container, Step step, List<StepExecution> children, List<Turn> history,
                                    String state) {
        return transcript(container, step, children, history, state, null);
    }

    /**
     * {@link #transcript(StepExecution, Step, List, List, String)}, with the process's sensitive variables kept
     * out of it: a value under a key named like one, and any occurrence of one of their current values in a
     * message, an argument or a result, reads as {@code ••••} — the transcript is shown in the console, REST
     * and MCP, where a sensitive value is never shown.
     */
    public static String transcript(StepExecution container, Step step, List<StepExecution> children, List<Turn> history,
                                    String state, Process process) {
        return io.mateu.workflow.application.services.SensitiveVariableMasking.redactDocument(
                transcriptOf(container, step, children, history, state), process);
    }

    private static String transcriptOf(StepExecution container, Step step, List<StepExecution> children, List<Turn> history,
                                       String state) {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(entry(-1, "user", cap(container.scope().getOrDefault("goal", ""), MAX_TRANSCRIPT_ENTRY_CHARS), null));
        for (var turn : history) {
            var assistant = entry(turn.index(), "assistant", cap(turn.text(), MAX_TRANSCRIPT_ENTRY_CHARS), null);
            if (!turn.calls().isEmpty()) {
                assistant.put("toolCalls", turn.calls().stream().map(call -> {
                    var map = new LinkedHashMap<String, Object>();
                    map.put("id", call.id());
                    map.put("name", call.name());
                    map.put("arguments", cap(redact(call.arguments()), MAX_TRANSCRIPT_ENTRY_CHARS));
                    return map;
                }).toList());
            }
            entries.add(assistant);
            var results = results(turn, children, step.agent());
            for (int i = 0; i < turn.calls().size(); i++) {
                var call = turn.calls().get(i);
                var result = results.get(i);
                var tool = entry(turn.index(), "tool", result == null ? null
                        : cap(redact(result.content()), MAX_TRANSCRIPT_ENTRY_CHARS), call.name());
                tool.put("callId", call.id());
                tool.put("outcome", result == null ? "running" : result.outcome());
                var child = find(children.stream().filter(c -> String.valueOf(turn.index()).equals(c.scope().get(TURN))).toList(),
                        KIND_TOOL, call.id());
                if (child != null) {
                    tool.put("executionId", child.getId());
                    tool.put("stepId", child.getStepId());
                }
                entries.add(tool);
            }
        }
        var document = new LinkedHashMap<String, Object>();
        document.put("state", state);
        document.putAll(usage(history));
        document.put("transcript", entries);
        var json = MultiInstance.toJson(document);
        int elided = 0;
        while (json.length() > MAX_TRANSCRIPT_CHARS && entries.size() > 2) {
            entries.remove(1);
            elided++;
            document.put("elided", elided);
            json = MultiInstance.toJson(document);
        }
        return json;
    }

    private static LinkedHashMap<String, Object> entry(int turn, String role, String content, String tool) {
        var map = new LinkedHashMap<String, Object>();
        if (turn >= 0) {
            map.put("turn", turn);
        }
        map.put("role", role);
        if (tool != null) {
            map.put("tool", tool);
        }
        if (content != null) {
            map.put("content", content);
        }
        return map;
    }

    /** A JSON document with every value under a secret-looking key replaced by {@code ***}; other text as is. */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        var trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return text;
        }
        try {
            var tree = JSON.readValue(trimmed, Object.class);
            return MultiInstance.toJson(redactValue(tree));
        } catch (Exception notJson) {
            return text;
        }
    }

    private static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put(String.valueOf(key),
                    SECRET_KEY.matcher(String.valueOf(key)).matches() ? "***" : redactValue(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AgentLoop::redactValue).toList();
        }
        if (value instanceof String text && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
            return redact(text);
        }
        return value;
    }

    static String cap(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "… [" + (text.length() - max) + " more characters]";
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
