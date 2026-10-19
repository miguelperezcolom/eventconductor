package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Definition errors of an AGENT_CALL step: the structural rules shared with the Maven plugin
 * ({@code AgentCallRules}), then what needs the engine — templates and output expressions that must
 * parse, JSON Schemas that must be JSON Schema, and each {@code http} tool checked as the HTTP_CALL it
 * runs as.
 */
public final class AgentCallValidation {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static List<String> problems(Step step, List<Step> steps) {
        var stepsById = new LinkedHashMap<String, Map<?, ?>>();
        for (var other : steps) {
            if (other.id() != null) {
                stepsById.put(other.id(), asMap(other));
            }
        }
        var problems = new ArrayList<>(io.mateu.workflow.analysis.AgentCallRules.problems(asMap(step), stepsById));
        var agent = step.agent();
        if (agent == null || !problems.isEmpty()) {
            return problems;
        }
        var where = "AGENT_CALL step '" + step.id() + "'";
        checkTemplate(agent.system(), where + " agent.system", problems);
        checkTemplate(agent.goal(), where + " agent.goal", problems);
        if (agent.output() != null) {
            if (agent.output().schema() != null) {
                AgentSchemas.problems(agent.output().schema())
                        .forEach(problem -> problems.add(where + " output.schema is not a valid JSON Schema — " + problem));
            }
            if (agent.output().mapping() != null) {
                agent.output().mapping().forEach((variable, expression) ->
                        checkTemplate("${" + expression + "}", where + " output.mapping." + variable, problems));
            }
        }
        for (var tool : agent.toolList()) {
            var at = where + " tool '" + tool.name() + "'";
            if (tool.inputSchema() != null) {
                AgentSchemas.problems(tool.inputSchema())
                        .forEach(problem -> problems.add(at + " inputSchema is not a valid JSON Schema — " + problem));
            }
            if ("http".equals(tool.kind())) {
                var http = AgentLoop.synthesize(step, tool, step.id() + "." + tool.name());
                HttpRequestRenderer.problems(http).forEach(problem -> problems.add(at + ": " + problem));
            }
            if (tool.approval() != null && tool.approval().assignment() != null) {
                problems.addAll(Templates.problems(tool.approval().assignment().asMap(), at + " approval"));
            }
        }
        return problems;
    }

    private static void checkTemplate(Object template, String where, List<String> problems) {
        if (template != null && !String.valueOf(template).contains("${secret:")) {
            problems.addAll(Templates.problems(template, where));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Step step) {
        var map = (Map<String, Object>) JSON.convertValue(step, Map.class);
        map.values().removeIf(Objects::isNull);
        return map;
    }

    private AgentCallValidation() {
    }
}
