package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

/**
 * Resolves the correlation key of a message step: the value of the step's
 * {@code correlationExpression} — a JEXL expression evaluated against process variables with
 * the same context (and the same fail-closed semantics) as {@code preconditionExpression}: an
 * expression that cannot be evaluated matches nothing.
 *
 * <p>On a WAIT_FOR_MESSAGE step the key is what an incoming message must carry to correlate;
 * on a SEND_MESSAGE step it is the key stamped on the outgoing message.
 *
 * <p>Legacy: steps persisted before the MESSAGE → WAIT_FOR_MESSAGE rename may carry no
 * {@code correlationExpression} (it used to be optional); for those the process businessKey
 * is used, preserving in-flight behavior. New definitions are rejected at load without one.
 */
@Slf4j
public class MessageCorrelation {

    public static boolean matches(Step step, Process process, String correlationKey) {
        var expected = expectedKey(step, process);
        return expected != null && expected.equals(correlationKey);
    }

    public static String expectedKey(Step step, Process process) {
        if (step.correlationExpression() == null || step.correlationExpression().isBlank()) {
            return process.getBusinessKey();
        }
        var context = new HashMap<String, Object>();
        context.put("process", process);
        context.put("step", step);
        if (process.getVariables() != null) {
            process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        }
        // Seeded AFTER the variables so the canonical value always wins: JEXL runs with
        // RESTRICTED permissions (no introspection of domain classes), so businessKey must
        // be available as a plain context variable — `process.businessKey` cannot evaluate.
        context.put("businessKey", process.getBusinessKey());
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
