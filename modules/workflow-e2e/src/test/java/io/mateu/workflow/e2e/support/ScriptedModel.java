package io.mateu.workflow.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.dtos.events.integration.TaskExecutionRequested;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A model for AGENT_CALL e2e tests: answers the agent's turns (the {@code agent-turn} task, dispatched
 * under the agent's step id) from a script — tool calls, then a final answer — and records every
 * request it was sent. A turn may also fail. Never a provider.
 */
public final class ScriptedModel implements TestWorker.Behavior {

    private static final ObjectMapper JSON = new ObjectMapper();

    private sealed interface Turn permits Calls, Answer, Failure {
    }

    private record Calls(List<Map<String, Object>> calls) implements Turn {
    }

    private record Answer(String text) implements Turn {
    }

    private record Failure(String reason) implements Turn {
    }

    private final List<Turn> script = new ArrayList<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private int tokensPerTurn;

    public static Map<String, Object> call(String name, Map<String, ?> arguments) {
        var call = new LinkedHashMap<String, Object>();
        call.put("name", name);
        call.put("arguments", arguments);
        return call;
    }

    @SafeVarargs
    public final ScriptedModel thenCall(Map<String, Object>... calls) {
        script.add(new Calls(List.of(calls)));
        return this;
    }

    public ScriptedModel thenAnswer(String text) {
        script.add(new Answer(text));
        return this;
    }

    public ScriptedModel thenFail(String reason) {
        script.add(new Failure(reason));
        return this;
    }

    public ScriptedModel costing(int tokensPerTurn) {
        this.tokensPerTurn = tokensPerTurn;
        return this;
    }

    public List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    /** The tool results the last request carried, as {@code {id, name, content}}. */
    public List<JsonNode> lastToolResults() {
        var messages = requests.getLast().path("messages");
        var last = messages.get(messages.size() - 1);
        var results = new ArrayList<JsonNode>();
        if ("tool".equals(last.path("role").asText())) {
            last.path("results").forEach(results::add);
        }
        return results;
    }

    @Override
    public void run(TaskExecutionRequested request, TestWorker.WorkerCallback callback, int invocation) {
        try {
            var agent = request.variables().stream().filter(v -> "__agent".equals(v.name())).findFirst().orElseThrow();
            requests.add(JSON.readTree(agent.value()));
            var turn = script.get(Math.min(requests.size() - 1, script.size() - 1));
            var answer = new LinkedHashMap<String, Object>();
            switch (turn) {
                case Failure failure -> {
                    callback.fail(failure.reason());
                    return;
                }
                case Answer final_ -> {
                    answer.put("text", final_.text());
                    answer.put("toolCalls", List.of());
                    if (final_.text().trim().startsWith("{")) {
                        answer.put("json", JSON.readValue(final_.text(), Object.class));
                    }
                }
                case Calls calls -> {
                    answer.put("text", "");
                    var list = new ArrayList<Map<String, Object>>();
                    for (int i = 0; i < calls.calls().size(); i++) {
                        var call = calls.calls().get(i);
                        list.add(Map.of("id", "call_" + requests.size() + "_" + i, "name", call.get("name"),
                                "arguments", JSON.writeValueAsString(call.get("arguments"))));
                    }
                    answer.put("toolCalls", list);
                }
            }
            answer.put("model", "scripted");
            answer.put("usage", Map.of("inputTokens", tokensPerTurn / 2, "outputTokens", tokensPerTurn - tokensPerTurn / 2));
            callback.complete(List.of(TestWorker.var("__agentAnswer", JSON.writeValueAsString(answer))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
