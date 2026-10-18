package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * An explicit back-edge: when this step completes and {@code while} holds, the section from
 * {@code to} to this step runs again. Carried in the definition as the step's {@code loopBack}
 * block. Ordinary precondition cycles stay an error; this is the one way to draw a loop.
 *
 * <p>The <em>section</em> is every step that lies on a path from {@code to} to this step, both
 * included. Looping back gives each of them a fresh execution — so JOIN barriers, XOR joins and
 * CHOICE latches inside it start over — while steps outside it keep what they did, and process
 * variables carry over untouched. Anything still in flight inside the section is cancelled.
 *
 * @param to            the step the loop goes back to; must be upstream of this step
 * @param condition     JEXL ({@code while} in the definition) over the process variables plus
 *                      {@code iteration}; absent = always loop (a CHOICE branch carrying the back-edge
 *                      is then the condition)
 * @param maxIterations the most times the section may run, the first pass included; required
 * @param delay         wait before {@code to} runs again: milliseconds or an ISO-8601 duration
 */
public record LoopBack(
        String to,
        @JsonProperty("while")
        String condition,
        int maxIterations,
        @JsonDeserialize(using = TimeoutDeserializer.class)
        long delay
) {

    public boolean unconditional() {
        return condition == null || condition.isBlank();
    }
}
