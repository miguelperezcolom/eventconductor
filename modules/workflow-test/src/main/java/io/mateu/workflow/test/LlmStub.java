package io.mateu.workflow.test;

import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.template.Templates;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The answers an {@code LLM_CALL} step gets instead of a model. The kit records the rendered request
 * ({@link #requests()}: {@code prompt}, {@code system}, {@code model} …) and answers with the next
 * text here, mapped onto the process the way the built-in {@code llm-call} task maps a real answer:
 * {@code outputVariable}, {@code usageVariable}, and {@code output} expressions over {@code text},
 * {@code json} (the answer parsed, when the step declares a {@code responseSchema} or the text is
 * JSON), {@code usage}, {@code model} and {@code finishReason}. The schema itself is not validated.
 *
 * <p>Answers are consumed in order and the last one repeats, as for {@link TaskStub}.
 */
public final class LlmStub {

    private record Answer(String text, TaskOutcome.Fail failure) {
    }

    private final String stepId;
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    LlmStub(String stepId) {
        this.stepId = stepId;
    }

    /** The model answers {@code text} (a JSON document for a step with a {@code responseSchema}). */
    public LlmStub thenAnswer(String text) {
        answers.add(new Answer(text, null));
        return this;
    }

    /** The call fails with {@code code} ({@code LLM_RATE_LIMITED}, {@code LLM_INVALID_OUTPUT} …). */
    public LlmStub thenFail(String code, boolean retryable) {
        answers.add(new Answer(null, new TaskOutcome.Fail(code, null, retryable)));
        return this;
    }

    /** Every request the step rendered, in order. */
    public List<Map<String, Object>> requests() {
        return List.copyOf(requests);
    }

    /** The last request rendered, or null. */
    public Map<String, Object> lastRequest() {
        return requests.isEmpty() ? null : requests.getLast();
    }

    TaskOutcome answer(JsonNode request) {
        requests.add(Values.toMap(request));
        if (answers.isEmpty()) {
            return TaskOutcome.fail("LLM_STUB", "LLM_CALL step '" + stepId + "' is stubbed but has no answer", false);
        }
        var answer = answers.get(Math.min(calls.getAndIncrement(), answers.size() - 1));
        if (answer.failure() != null) {
            return answer.failure();
        }
        var text = answer.text();
        var schema = request.path("responseSchema");
        Object json = Values.parsed(text);
        if (json instanceof String && schema.isObject() && !schema.isEmpty()) {
            return TaskOutcome.fail("LLM_INVALID_OUTPUT", "the answer is not a JSON document", true);
        }
        if (json instanceof String) {
            json = null;
        }
        var model = request.hasNonNull("model") ? request.path("model").asText() : "stub";
        var usage = new LinkedHashMap<String, Object>();
        usage.put("model", model);
        usage.put("inputTokens", 0);
        usage.put("outputTokens", 0);
        usage.put("totalTokens", 0);
        var result = new LinkedHashMap<String, Object>();
        var outputVariable = request.path("outputVariable").asText("");
        if (!outputVariable.isBlank()) {
            result.put(outputVariable, text);
        }
        var usageVariable = request.path("usageVariable").asText("");
        if (!usageVariable.isBlank()) {
            result.put(usageVariable, usage);
        }
        var output = request.path("output");
        if (output.isObject()) {
            var context = new LinkedHashMap<String, Object>();
            context.put("text", text);
            context.put("json", json);
            context.put("usage", usage);
            context.put("model", model);
            context.put("finishReason", "stop");
            for (var entry : output.properties()) {
                try {
                    result.put(entry.getKey(), Templates.render("${" + entry.getValue().asText() + "}", context));
                } catch (Templates.TemplateException e) {
                    return TaskOutcome.fail("LLM_OUTPUT", "output '" + entry.getKey() + "': " + e.getMessage(), false);
                }
            }
        }
        return TaskOutcome.complete(result);
    }
}
