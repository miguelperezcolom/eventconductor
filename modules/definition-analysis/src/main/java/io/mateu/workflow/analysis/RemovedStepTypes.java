package io.mateu.workflow.analysis;

import java.util.Map;
import java.util.Optional;

/**
 * Step types EventConductor once accepted and no longer does, each with what to use instead — shared as
 * code by the engine and the Maven plugin, so a definition that still uses one fails the build with the
 * same message it fails to load with. Pure: a type name in, a message out.
 */
public final class RemovedStepTypes {

    /** Where the replacement for the removed agent step type is documented. */
    public static final String AGENT_GUIDE =
            "https://github.com/miguelperezcolom/eventconductor/blob/main/doc/src/content/docs/guides/workflow-agents.md";

    /** Error code a step execution of a removed type fails with when a process persisted before reaches it. */
    public static final String ERROR_CODE = "STEP_TYPE_REMOVED";

    private static final Map<String, String> REMOVED = Map.of(
            "AGENT_CALL", "AGENT_CALL was removed in 2.34.0; use the AGENT step (agents from the IA catalogue) — see "
                    + AGENT_GUIDE);

    /** Whether {@code type} names a step type that was removed. */
    public static boolean isRemoved(String type) {
        return type != null && REMOVED.containsKey(type);
    }

    /** Why {@code type} is not accepted any more and what to use instead; empty when it was not removed. */
    public static Optional<String> message(String type) {
        return type == null ? Optional.empty() : Optional.ofNullable(REMOVED.get(type));
    }

    /**
     * The message for a step a definition declares with a removed {@code type}, naming the step; empty when
     * the type was not removed.
     */
    public static Optional<String> violation(String stepId, String type) {
        return message(type).map(message -> "Step '" + stepId + "': " + message);
    }

    private RemovedStepTypes() {
    }
}
