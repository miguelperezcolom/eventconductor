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
 * JSON), {@code usage}, {@code model} and {@code finishReason}.
 *
 * <p>An answer is checked against the step's {@code responseSchema}, as the built-in task checks a
 * model's: a {@link #thenAnswer} the schema refuses is a mistake in the test — the step fails with
 * {@code LLM_STUB_INVALID} and the test fails, naming the stub and what the schema says. To exercise
 * the step's handling of a model that answers badly, say so with {@link #thenInvalidAnswer}: it fails
 * the call with {@code LLM_INVALID_OUTPUT} and the schema's errors (retryable), as a real one would.
 *
 * <p>Answers are consumed in order and the last one repeats, as for {@link TaskStub}.
 */
public final class LlmStub {

    private record Answer(String text, TaskOutcome.Fail failure, boolean invalid) {
    }

    private final String stepId;
    private final java.util.function.Consumer<String> problems;
    private final List<Answer> answers = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    LlmStub(String stepId) {
        this(stepId, problem -> { });
    }

    LlmStub(String stepId, java.util.function.Consumer<String> problems) {
        this.stepId = stepId;
        this.problems = problems;
    }

    /**
     * The model answers {@code text} — for a step with a {@code responseSchema}, a JSON document valid
     * against it: an answer the schema refuses fails the test (see the class comment).
     */
    public LlmStub thenAnswer(String text) {
        answers.add(new Answer(text, null, false));
        return this;
    }

    /**
     * The model answers {@code text}, which the step's {@code responseSchema} is expected to refuse: the call
     * fails with {@code LLM_INVALID_OUTPUT} and the schema's errors, retryably — as the built-in task fails
     * a real model's invalid answer, so the step spends its {@code retries}.
     */
    public LlmStub thenInvalidAnswer(String text) {
        answers.add(new Answer(text, null, true));
        return this;
    }

    /** The call fails with {@code code} ({@code LLM_RATE_LIMITED}, {@code LLM_INVALID_OUTPUT} …). */
    public LlmStub thenFail(String code, boolean retryable) {
        answers.add(new Answer(null, new TaskOutcome.Fail(code, null, retryable), false));
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
        if (schema.isObject() && !schema.isEmpty()) {
            var errors = json instanceof String || json == null ? List.of("the answer is not a JSON document")
                    : io.mateu.workflow.domain.services.JsonSchemas.validate(Values.toMap(schema), Values.text(json));
            if (!errors.isEmpty()) {
                var why = "the answer does not match the responseSchema — " + String.join("; ", errors);
                if (answer.invalid()) {
                    return TaskOutcome.fail("LLM_INVALID_OUTPUT", why, true);
                }
                var problem = "LLM_CALL step '" + stepId + "': the stubbed answer does not match the step's responseSchema — "
                        + String.join("; ", errors) + ". Fix the stub, or stub it with thenInvalidAnswer(…) to test an"
                        + " invalid model answer. Answer: " + text;
                problems.accept(problem);
                return TaskOutcome.fail("LLM_STUB_INVALID", problem, false);
            }
        } else if (answer.invalid()) {
            var problem = "LLM_CALL step '" + stepId + "' has no responseSchema, so thenInvalidAnswer(…) has nothing to"
                    + " be invalid against; use thenFail(\"LLM_INVALID_OUTPUT\", true)";
            problems.accept(problem);
            return TaskOutcome.fail("LLM_STUB_INVALID", problem, false);
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
