package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The scripted model of an {@code AGENT_CALL} step: each turn of the agent gets the next answer here
 * instead of a provider's — a request for one or more tool calls, a final answer, or a failure. The
 * tools themselves run as the step executions they are and are answered by the ordinary stubs
 * ({@code whenStep} for a {@code step:} tool, {@code whenHttpCall("<agent>.<tool>")} for an
 * {@code http:} one, {@code whenTask} for a {@code task:} one, a child process for a {@code process:} one,
 * {@code completeUserTask("<agent>.<tool>.approval", …)} for an approval).
 *
 * <pre>{@code
 * runtime.whenAgent("triage")
 *         .thenCallTool("lookupOrder", Map.of("orderId", "O-1"))
 *         .thenCallTools(AgentStub.call("openRefund", Map.of("orderId", "O-1", "amount", 20)),
 *                        AgentStub.call("notifyCustomer", Map.of("text", "Refund on its way")))
 *         .thenAnswer("{\"decision\":\"refund\"}");
 * }</pre>
 *
 * <p>Turns are consumed in order; once the script is spent, the last answer repeats. Every request is
 * recorded ({@link #requests()}): the system prompt, the tools and the messages — the tool results the
 * model would have seen included. The output schema is not checked here; the step's mapping is.
 */
public final class AgentStub {

    /** One scripted tool call: the tool's name and its arguments (a map, or JSON text). */
    public record ToolCall(String name, Object arguments) {
    }

    private record Turn(List<ToolCall> calls, String text, TaskOutcome.Fail failure, int inputTokens, int outputTokens) {
    }

    private final String stepId;
    private final List<Turn> turns = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int inputTokensPerTurn;
    private volatile int outputTokensPerTurn;

    AgentStub(String stepId) {
        this.stepId = stepId;
    }

    /** A tool call for {@link #thenCallTools}. */
    public static ToolCall call(String tool, Object arguments) {
        return new ToolCall(tool, arguments);
    }

    /** The model asks for one tool call. */
    public AgentStub thenCallTool(String tool, Object arguments) {
        return thenCallTools(call(tool, arguments));
    }

    /** The model asks for several tool calls in one turn (they run up to {@code toolConcurrency} at once). */
    public AgentStub thenCallTools(ToolCall... toolCalls) {
        turns.add(new Turn(List.of(toolCalls), "", null, inputTokensPerTurn, outputTokensPerTurn));
        return this;
    }

    /** The model gives its final answer (a JSON document for a step with an output schema). */
    public AgentStub thenAnswer(String text) {
        turns.add(new Turn(List.of(), text, null, inputTokensPerTurn, outputTokensPerTurn));
        return this;
    }

    /** The turn fails with {@code code} ({@code LLM_429}, {@code LLM_INVALID_OUTPUT} …). */
    public AgentStub thenFail(String code, boolean retryable) {
        turns.add(new Turn(List.of(), null, new TaskOutcome.Fail(code, null, retryable), 0, 0));
        return this;
    }

    /** The usage the following turns report — to test {@code maxTokens}. */
    public AgentStub costing(int inputTokens, int outputTokens) {
        this.inputTokensPerTurn = inputTokens;
        this.outputTokensPerTurn = outputTokens;
        return this;
    }

    /** Every turn's request, in order: {@code system}, {@code tools}, {@code messages} … */
    public List<Map<String, Object>> requests() {
        return List.copyOf(requests);
    }

    /** The last turn's request, or null. */
    public Map<String, Object> lastRequest() {
        return requests.isEmpty() ? null : requests.getLast();
    }

    /**
     * The tool results the model was given in the last request, in order: each {@code {id, name, content}},
     * {@code content} parsed when it is JSON.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> lastToolResults() {
        var request = lastRequest();
        var results = new ArrayList<Map<String, Object>>();
        if (request == null || !(request.get("messages") instanceof List<?> messages) || messages.isEmpty()) {
            return results;
        }
        var last = messages.getLast();
        if (last instanceof Map<?, ?> message && "tool".equals(message.get("role")) && message.get("results") instanceof List<?> list) {
            for (var item : list) {
                var result = new LinkedHashMap<>((Map<String, Object>) item);
                result.put("content", Values.parsed(String.valueOf(result.get("content"))));
                results.add(result);
            }
        }
        return results;
    }

    TaskOutcome answer(JsonNode request) {
        requests.add(Values.toMap(request));
        if (turns.isEmpty()) {
            return TaskOutcome.fail("LLM_STUB", "AGENT_CALL step '" + stepId + "' is stubbed but has no scripted turn", false);
        }
        int index = calls.getAndIncrement();
        var turn = turns.get(Math.min(index, turns.size() - 1));
        if (turn.failure() != null) {
            return turn.failure();
        }
        var answer = new LinkedHashMap<String, Object>();
        answer.put("text", turn.text());
        var json = turn.text() == null ? null : Values.parsed(turn.text());
        if (!(json instanceof String) && json != null) {
            answer.put("json", json);
        }
        var toolCalls = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < turn.calls().size(); i++) {
            var call = turn.calls().get(i);
            var map = new LinkedHashMap<String, Object>();
            map.put("id", "call_" + index + "_" + i);
            map.put("name", call.name());
            map.put("arguments", call.arguments() instanceof String s ? s : Values.text(call.arguments()));
            toolCalls.add(map);
        }
        answer.put("toolCalls", toolCalls);
        answer.put("model", request.hasNonNull("model") ? request.path("model").asText() : "stub");
        answer.put("finishReason", toolCalls.isEmpty() ? "stop" : "tool_use");
        answer.put("usage", Map.of("inputTokens", turn.inputTokens(), "outputTokens", turn.outputTokens()));
        return TaskOutcome.complete(Map.of("__agentAnswer", answer));
    }
}
