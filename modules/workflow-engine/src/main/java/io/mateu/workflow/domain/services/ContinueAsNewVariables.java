package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.Variable;
import io.mateu.workflow.template.Templates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The variables a CONTINUE_AS_NEW carries to the next run (its {@code continueAsNew.variables}): absent —
 * every variable of the process; a list of names — those that are set; a map — each template rendered over
 * the process (a non-text value becomes its JSON text, as process variables are).
 */
public final class ContinueAsNewVariables {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private ContinueAsNewVariables() {
    }

    /** @param problems told about each template that could not be rendered (its name is then not carried) */
    public static List<Variable> of(Step step, Process process, Consumer<String> problems) {
        var declared = step.continueAsNew() == null ? null : step.continueAsNew().variables();
        var current = process.getVariables();
        if (declared == null) {
            return List.copyOf(current);
        }
        var carried = new ArrayList<Variable>();
        if (declared instanceof Collection<?> names) {
            var wanted = new LinkedHashSet<String>();
            names.forEach(name -> wanted.add(String.valueOf(name)));
            for (var name : wanted) {
                current.stream().filter(v -> name.equals(v.name())).findFirst().ifPresent(carried::add);
            }
            return carried;
        }
        if (declared instanceof Map<?, ?> templates) {
            var context = TemplateContext.of(process, step, null);
            templates.forEach((name, template) -> {
                try {
                    var value = Templates.render(template, context);
                    carried.add(new Variable(String.valueOf(name), text(value)));
                } catch (Templates.TemplateException | IllegalArgumentException e) {
                    problems.accept("continueAsNew variable '" + name + "' could not be rendered: " + e.getMessage());
                }
            });
        }
        return carried;
    }

    private static String text(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
