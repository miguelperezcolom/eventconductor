package io.mateu.workflow.test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.mateu.workflow.domain.aggregates.ProcessStatus;
import io.mateu.workflow.template.Templates;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Runs a {@link Scenario} against a {@link WorkflowTestRuntime}: stubs, start, actions, expectations. */
final class ScenarioRunner {

    private static final Set<String> KEYS = Set.of("name", "workflow", "businessKey", "variables", "tasks", "steps",
            "http", "llm", "agents", "then", "expect", "description");
    private static final Set<String> EXPECTATIONS = Set.of("status", "passedSteps", "notPassedSteps", "waitingAt",
            "variables", "missingVariables", "publishedEvents", "invocations", "reply");

    private final WorkflowTestRuntime runtime;
    private final Scenario scenario;

    ScenarioRunner(WorkflowTestRuntime runtime, Scenario scenario) {
        this.runtime = runtime;
        this.scenario = scenario;
    }

    TestProcess run() {
        var spec = scenario.spec();
        spec.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) {
                throw invalid("unknown key '" + key + "' (known: " + new java.util.TreeSet<>(KEYS) + ")");
            }
        });
        // Every answer is checked before the first stub is set: a mistake leaves no half-configured mock behind.
        for (var field : List.of("tasks", "steps")) {
            objectOf(spec, field).properties().forEach(e -> asList(e.getValue())
                    .forEach(answer -> taskOutcome(answer, e.getKey(), Map.of(), true)));
        }
        objectOf(spec, "tasks").properties().forEach(e -> taskAnswers(runtime.whenTask(e.getKey()), e.getKey(), e.getValue()));
        objectOf(spec, "steps").properties().forEach(e -> taskAnswers(runtime.whenStep(e.getKey()), e.getKey(), e.getValue()));
        objectOf(spec, "http").properties().forEach(e -> httpAnswers(runtime.whenHttpCall(e.getKey()), e.getKey(), e.getValue()));
        objectOf(spec, "llm").properties().forEach(e -> llmAnswers(runtime.whenLlmCall(e.getKey()), e.getKey(), e.getValue()));
        objectOf(spec, "agents").properties().forEach(e -> agentAnswers(runtime.whenAgent(e.getKey()), e.getKey(), e.getValue()));

        var businessKey = spec.hasNonNull("businessKey") ? spec.get("businessKey").asText() : null;
        var process = runtime.start(scenario.workflow(), businessKey, map(spec.get("variables")));
        var step = 0;
        for (var action : listOf(spec, "then")) {
            step++;
            act(process, action, step);
        }
        expect(process, spec.path("expect"));
        return process;
    }

    // ---------------------------------------------------------------- answers

    private void taskAnswers(TaskStub stub, String key, JsonNode answers) {
        var list = asList(answers);
        if (list.stream().anyMatch(answer -> answer.has("when"))) {
            // Rules: the first answer whose `when` holds over what the task was sent (one without `when` always does).
            var rules = List.copyOf(list);
            stub.thenAnswer(invocation -> {
                var context = context(invocation);
                for (var rule : rules) {
                    if (!rule.has("when") || holds(rule.get("when").asText(), context, key)) {
                        return taskOutcome(rule, key, context, false);
                    }
                }
                throw invalid("no answer of '" + key + "' applies to " + invocation.variables());
            });
            return;
        }
        for (var answer : list) {
            stub.thenAnswer(invocation -> taskOutcome(answer, key, context(invocation), false));
        }
    }

    private TaskOutcome taskOutcome(JsonNode answer, String key, Map<String, Object> context, boolean checkOnly) {
        if (answer.has("complete")) {
            if (checkOnly) {
                return null;
            }
            var variables = map(answer.get("complete"));
            var rendered = new LinkedHashMap<String, Object>();
            variables.forEach((name, value) -> rendered.put(name, render(value, context, key)));
            return TaskOutcome.complete(rendered);
        }
        if (answer.has("fail")) {
            return TaskOutcome.fail(answer.get("fail").asText(), answer.path("retryable").asBoolean(true));
        }
        if (answer.path("timeout").asBoolean(false)) {
            return TaskOutcome.noReply();
        }
        throw invalid("the answer " + answer + " of '" + key + "' says neither complete, fail nor timeout");
    }

    private void httpAnswers(HttpStub stub, String key, JsonNode answers) {
        for (var answer : asList(answers)) {
            if (answer.path("ioError").asBoolean(false)) {
                stub.thenIoError();
                continue;
            }
            var status = answer.path("status").asInt(200);
            var body = answer.get("body");
            var headers = new LinkedHashMap<String, String>();
            answer.path("headers").properties().forEach(h -> headers.put(h.getKey(), h.getValue().asText()));
            stub.thenRespond(status, body == null || body.isNull() ? null : body.isTextual() ? body.asText() : body.toString(),
                    headers);
        }
    }

    private void llmAnswers(LlmStub stub, String key, JsonNode answers) {
        for (var answer : asList(answers)) {
            if (answer.has("fail")) {
                stub.thenFail(answer.get("fail").asText(), answer.path("retryable").asBoolean(true));
            } else if (answer.has("answer")) {
                var text = answer.get("answer");
                stub.thenAnswer(text.isTextual() ? text.asText() : text.toString());
            } else {
                throw invalid("the answer " + answer + " of LLM_CALL '" + key + "' says neither answer nor fail");
            }
        }
    }

    private void agentAnswers(AgentStub stub, String key, JsonNode answers) {
        for (var answer : asList(answers)) {
            if (answer.has("output")) {
                var output = answer.get("output");
                stub.thenOutput(output.isTextual() ? output.asText() : output.toString());
            } else if (answer.has("fail")) {
                stub.thenFail(answer.get("fail").asText(), answer.path("message").asText(null));
            } else if (answer.path("reviewRequired").asBoolean(false)) {
                stub.thenReviewRequired();
            } else if (answer.path("cancelled").asBoolean(false)) {
                stub.thenCancelled();
            } else {
                throw invalid("the answer " + answer + " of AGENT '" + key
                        + "' says neither output, fail, reviewRequired nor cancelled");
            }
        }
    }

    // ---------------------------------------------------------------- actions

    private void act(TestProcess process, JsonNode action, int index) {
        if (!action.isObject() || action.size() != 1) {
            throw invalid("action " + index + " of 'then' must be an object with one key, e.g. {advanceTime: PT1H}");
        }
        var entry = action.properties().iterator().next();
        var arguments = entry.getValue();
        switch (entry.getKey()) {
            case "advanceTime" -> runtime.advanceTime(Duration.parse(arguments.asText()));
            case "sendMessage" -> runtime.sendMessage(required(arguments, "name", entry.getKey()),
                    arguments.hasNonNull("correlationKey") ? arguments.get("correlationKey").asText() : process.businessKey(),
                    map(arguments.get("variables")));
            case "completeTask" -> runtime.completeTask(process, required(arguments, "step", entry.getKey()),
                    map(arguments.get("variables")));
            case "failTask" -> runtime.failTask(process, required(arguments, "step", entry.getKey()),
                    required(arguments, "code", entry.getKey()), arguments.path("retryable").asBoolean(true));
            case "completeUserTask" -> runtime.completeUserTask(process, required(arguments, "step", entry.getKey()),
                    map(arguments.has("values") ? arguments.get("values") : arguments.get("variables")));
            case "publishEvent" -> runtime.publishEvent(required(arguments, "destination", entry.getKey()),
                    required(arguments, "type", entry.getKey()),
                    arguments.get("data") == null ? null : arguments.get("data").toString());
            case "update" -> runtime.update(process, required(arguments, "name", entry.getKey()),
                    arguments.get("payload") == null ? null : arguments.get("payload").toString());
            case "cancel" -> runtime.cancel(process);
            case "runUntilIdle" -> runtime.runUntilIdle();
            default -> throw invalid("unknown action '" + entry.getKey() + "' (known: advanceTime, sendMessage, "
                    + "completeTask, failTask, completeUserTask, publishEvent, update, cancel, runUntilIdle)");
        }
    }

    // ---------------------------------------------------------------- expectations

    private void expect(TestProcess process, JsonNode expect) {
        if (expect.isMissingNode() || expect.isNull()) {
            return;
        }
        expect.fieldNames().forEachRemaining(key -> {
            if (!EXPECTATIONS.contains(key)) {
                throw invalid("unknown expectation '" + key + "' (known: " + new java.util.TreeSet<>(EXPECTATIONS) + ")");
            }
        });
        var assertion = WorkflowAssertions.assertThat(process).as("scenario '%s' (%s)", scenario.name(), scenario.source());
        if (expect.hasNonNull("status")) {
            ProcessStatus status;
            try {
                status = ProcessStatus.valueOf(expect.get("status").asText().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw invalid("unknown status '" + expect.get("status").asText() + "' (one of "
                        + List.of(ProcessStatus.values()) + ")");
            }
            assertion.hasStatus(status);
        }
        var passed = strings(expect.get("passedSteps"));
        if (!passed.isEmpty()) {
            assertion.hasPassedSteps(passed.toArray(String[]::new));
        }
        var notPassed = strings(expect.get("notPassedSteps"));
        if (!notPassed.isEmpty()) {
            assertion.hasNotPassedSteps(notPassed.toArray(String[]::new));
        }
        if (expect.hasNonNull("waitingAt")) {
            assertion.isWaitingAt(expect.get("waitingAt").asText());
        }
        map(expect.get("variables")).forEach(assertion::hasVariable);
        strings(expect.get("missingVariables")).forEach(assertion::doesNotHaveVariable);
        strings(expect.get("publishedEvents")).forEach(assertion::hasPublishedEvent);
        if (expect.hasNonNull("reply")) {
            var reply = expect.get("reply");
            assertion.hasReplied(reply.isTextual() ? reply.asText() : reply.toString());
        }
        expect.path("invocations").properties().forEach(entry -> {
            var actual = runtime.invocationsOf(entry.getKey()).stream()
                    .filter(invocation -> process.id().equals(invocation.processId())).count();
            if (actual != entry.getValue().asLong()) {
                throw new AssertionError("Scenario '" + scenario.name() + "' (" + scenario.source() + "): expected step '"
                        + entry.getKey() + "' to have been dispatched " + entry.getValue().asLong() + " time(s) but it was "
                        + actual + " time(s)");
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> context(TaskInvocation invocation) {
        var context = new LinkedHashMap<String, Object>();
        invocation.variables().forEach((name, value) -> context.put(name, Values.parsed(value)));
        return context;
    }

    private boolean holds(String condition, Map<String, Object> context, String key) {
        try {
            var value = Templates.render("${" + condition + "}", context);
            return Boolean.TRUE.equals(value) || "true".equals(value);
        } catch (Templates.TemplateException e) {
            throw invalid("the condition '" + condition + "' of '" + key + "' does not evaluate: " + e.getMessage());
        }
    }

    private Object render(Object value, Map<String, Object> context, String key) {
        try {
            return Templates.render(value, context);
        } catch (Templates.TemplateException e) {
            throw invalid("an answer of '" + key + "' does not render: " + e.getMessage());
        }
    }

    private static List<JsonNode> asList(JsonNode node) {
        var list = new ArrayList<JsonNode>();
        if (node == null || node.isNull()) {
            return list;
        }
        if (node.isArray()) {
            node.forEach(list::add);
        } else {
            list.add(node);
        }
        return list;
    }

    private static List<JsonNode> listOf(JsonNode spec, String field) {
        return asList(spec.get(field));
    }

    private JsonNode objectOf(JsonNode spec, String field) {
        var node = spec.get(field);
        if (node == null || node.isNull()) {
            return Scenario.JSON.createObjectNode();
        }
        if (!node.isObject()) {
            throw invalid("'" + field + "' must be a map of step or task ids to answers");
        }
        return node;
    }

    private Map<String, Object> map(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw invalid("expected a map of variables but found " + node);
        }
        return Scenario.JSON.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static List<String> strings(JsonNode node) {
        var list = new ArrayList<String>();
        asList(node).forEach(item -> list.add(item.asText()));
        return list;
    }

    private String required(JsonNode arguments, String field, String action) {
        if (arguments == null || !arguments.hasNonNull(field)) {
            throw invalid("'" + action + "' needs '" + field + "'");
        }
        return arguments.get(field).asText();
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Scenario '" + scenario.name() + "' (" + scenario.source() + "): " + message);
    }
}
