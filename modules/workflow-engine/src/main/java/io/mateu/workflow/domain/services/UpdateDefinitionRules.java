package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.StepType;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What makes a definition's {@code updates} well formed — checked at load, so an update that could
 * never be applied is refused with the definition instead of failing on every call.
 */
public final class UpdateDefinitionRules {

    /** An update's name: it is a path segment of the API, and a word in the MCP tools. */
    static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,99}");

    /** At most this many updates per definition. */
    public static final int MAX_UPDATES = 50;

    public static List<String> violations(WorkflowDefinition definition) {
        var updates = definition.updates();
        var violations = new ArrayList<String>();
        if (updates == null) {
            return violations;
        }
        if (updates.size() > MAX_UPDATES) {
            violations.add("a definition declares at most " + MAX_UPDATES + " updates, not " + updates.size());
        }
        var steps = definition.steps() == null ? Map.<String, Step>of() : definition.steps().stream()
                .filter(step -> step.id() != null)
                .collect(Collectors.toMap(Step::id, step -> step, (a, b) -> a));
        var names = new HashSet<String>();
        for (int i = 0; i < updates.size(); i++) {
            var update = updates.get(i);
            if (update == null) {
                violations.add("updates[" + i + "] is empty");
                continue;
            }
            var where = "updates[" + i + "]" + (update.name() == null ? "" : " '" + update.name() + "'");
            if (update.name() == null || !NAME.matcher(update.name()).matches()) {
                violations.add(where + ": name must be a word (letters, digits, '_', '.', '-'; starting with a letter)");
            } else if (!names.add(update.name())) {
                violations.add(where + ": the name is declared twice");
            }
            if (update.accepts() != null) {
                AgentSchemas.problems(update.accepts())
                        .forEach(problem -> violations.add(where + ": accepts is not a valid JSON Schema (" + problem + ")"));
            }
            if (update.acceptedWhile() != null) {
                for (var stepId : update.acceptedWhile()) {
                    if (stepId == null || !steps.containsKey(stepId)) {
                        violations.add(where + ": acceptedWhile names a step that does not exist: '" + stepId + "'");
                    }
                }
            }
            if (update.completes() != null) {
                var step = steps.get(update.completes());
                if (step == null) {
                    violations.add(where + ": completes names a step that does not exist: '" + update.completes() + "'");
                } else if (step.type() != StepType.WAIT_FOR_MESSAGE) {
                    violations.add(where + ": completes must name a WAIT_FOR_MESSAGE step, and '"
                            + update.completes() + "' is a " + step.type());
                }
            }
            if (update.validate() != null) {
                for (int v = 0; v < update.validate().size(); v++) {
                    var validation = update.validate().get(v);
                    if (validation == null || validation.expression() == null || validation.expression().isBlank()) {
                        violations.add(where + ": validate[" + v + "] needs an expression");
                    } else {
                        expressionProblem(validation.expression(), where + " validate[" + v + "]", violations);
                    }
                }
            }
            if (update.set() != null) {
                update.set().forEach((variable, expression) -> {
                    if (variable == null || variable.isBlank()) {
                        violations.add(where + ": set has a variable with no name");
                    } else if (expression == null || expression.isBlank()) {
                        violations.add(where + ": set." + variable + " needs an expression");
                    } else {
                        expressionProblem(expression, where + " set." + variable, violations);
                    }
                });
            }
            if (update.reply() != null && update.replyTemplate() != null) {
                violations.add(where + ": declares both reply and replyTemplate; it must declare one");
            }
            if (update.reply() != null) {
                expressionProblem(update.reply(), where + " reply", violations);
            }
            if (update.replyTemplate() != null) {
                violations.addAll(io.mateu.workflow.template.Templates.problems(update.replyTemplate(), where + " replyTemplate"));
            }
        }
        return violations;
    }

    private static void expressionProblem(String expression, String where, List<String> violations) {
        var problem = io.mateu.workflow.application.services.JEXLEvaluator.syntaxProblem(expression);
        if (problem != null) {
            violations.add(where + ": the expression does not parse (" + problem + ")");
        }
    }

    private UpdateDefinitionRules() {
    }
}
