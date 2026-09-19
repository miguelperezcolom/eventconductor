package io.mateu.workflow.domain.services;

import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;

import static io.mateu.workflow.application.services.JEXLEvaluator.eval;

/**
 * Resolves the key a LOCK/UNLOCK step locks on: the value of the step's {@code lockKey} — a JEXL
 * expression evaluated against the process variables, with the same context and the same
 * fail-closed semantics as {@code correlationExpression} (see {@link MessageCorrelation}).
 *
 * <p>An expression that cannot be evaluated yields null; the caller treats a null key as a
 * misconfiguration and errors the step rather than silently locking on nothing.
 */
@Slf4j
public class LockKeyResolver {

    /** The key a LOCK/UNLOCK step locks on. */
    public static String resolve(Step step, Process process) {
        if (step.lockKey() == null || step.lockKey().isBlank()) {
            return null;
        }
        return resolveExpression(step.lockKey(), process,
                "lockKey '" + step.lockKey() + "' for step " + step.id());
    }

    /** The key a process-level lock ({@code processLock.key}) serializes on. */
    public static String resolveExpression(String expression, Process process, String what) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        var context = new HashMap<String, Object>();
        context.put("process", process);
        if (process.getVariables() != null) {
            process.getVariables().forEach(variable -> context.put(variable.name(), variable.value()));
        }
        // Seeded after the variables so the canonical value always wins, matching MessageCorrelation:
        // JEXL runs RESTRICTED, so businessKey must be a plain context variable.
        context.put("businessKey", process.getBusinessKey());
        try {
            var result = eval(expression, context);
            return result == null ? null : result.toString();
        } catch (Exception e) {
            log.error("Error evaluating " + what, e);
            return null;
        }
    }

    private LockKeyResolver() {
    }
}
