package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Run a single step again until a condition holds — the polling pattern. Carried in the definition
 * as the step's {@code repeat} block.
 *
 * <p>Each time the step completes, {@code until} is evaluated once, over the process variables
 * (with what the step just reported already written) plus {@code iteration}, the 1-based number of
 * the run that just completed. True: the step is done and the flow moves on. False: a new execution
 * of the step is created and starts after {@code delay}. A step that would need more than
 * {@code maxIterations} runs fails instead (final, not retried).
 *
 * @param until         JEXL condition that ends the repetition
 * @param maxIterations the most times the step may run, the first included; required, at least 1
 * @param delay         wait before each new run: milliseconds or an ISO-8601 duration; 0 = at once
 */
public record Repeat(
        String until,
        int maxIterations,
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long delay
) {
}
