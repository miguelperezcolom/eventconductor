package io.mateu.workflow.application.usecases.correlatemessage;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

/**
 * Resolves the correlation key a MESSAGE step expects: the process businessKey by default,
 * or the value of the step's {@code correlationExpression} — a JEXL expression evaluated
 * against process variables with the same context (and the same fail-closed semantics) as
 * {@code preconditionExpression}: an expression that cannot be evaluated matches nothing.
 */
@Slf4j
public class MessageCorrelation {

    public static boolean matches(Step step, Process process, String correlationKey) {
        var expected = expectedKey(step, process);
        return expected != null && expected.equals(correlationKey);
    }

    static String expectedKey(Step step, Process process) {
        if (step.correlationExpression() == null || step.correlationExpression().isBlank()) {
            return process.getBusinessKey();
        }
        var context = new HashMap<String, Object>();
        context.put("process", process);
        context.put("step", step);
        if (process.getVariables() != null) {
            process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        }
        try {
            var result = eval(step.correlationExpression(), context);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            // Fail closed: a correlation key that cannot be computed must not match anything.
            log.error("Error evaluating correlation expression '" + step.correlationExpression()
                    + "' for step " + step.id() + ", message will not correlate", e);
            return null;
        }
    }

    private MessageCorrelation() {
    }
}
