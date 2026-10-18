package io.mateu.workflow.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The structural rules of a step's {@code awaitReply} — the reply an {@code HTTP_CALL} or a
 * {@code PUBLISH_EVENT} waits for after sending its request — shared as code by the engine and the
 * Maven plugin, so a definition the build accepts is one the engine imports, and vice versa.
 * Correlation expressions and event templates are checked by whoever holds a JEXL engine; these are
 * the rules that need none.
 *
 * <ul>
 *   <li>Only an {@code HTTP_CALL} or a {@code PUBLISH_EVENT} reads it: anywhere else it would be ignored
 *       (a {@code WAIT_FOR_MESSAGE} already is a wait).</li>
 *   <li>{@code messageName} and {@code correlationExpression} are required, as on a
 *       {@code WAIT_FOR_MESSAGE}.</li>
 *   <li>{@code onEvent}, when present, follows the destination and type rules of an {@code onEvent}
 *       ({@link EventTriggerRules#sourceViolations}).</li>
 *   <li>The step's own {@code onEvent}, {@code messageName} and {@code correlationExpression} are not
 *       read on these step types; declaring them next to an {@code awaitReply} is reported, since the
 *       author almost certainly meant them inside it.</li>
 * </ul>
 */
public final class AwaitReplyRules {

    /** The step types that may await a reply to their request. */
    public static final Set<String> STEP_TYPES = Set.of("HTTP_CALL", "PUBLISH_EVENT");

    private AwaitReplyRules() {
    }

    /**
     * What is wrong with a step's {@code awaitReply}; empty means valid (or no {@code awaitReply}).
     *
     * @param stepId                the step's id, for the messages
     * @param stepType              the step's type
     * @param hasAwaitReply         whether the step declares an {@code awaitReply}
     * @param messageName           its {@code messageName}
     * @param correlationExpression its {@code correlationExpression}
     * @param hasOnEvent            whether it declares an {@code onEvent}
     * @param onEventDestination    that {@code onEvent}'s destination
     * @param onEventType           that {@code onEvent}'s type
     */
    public static List<String> violations(String stepId, String stepType, boolean hasAwaitReply,
                                          String messageName, String correlationExpression,
                                          boolean hasOnEvent, String onEventDestination, String onEventType) {
        if (!hasAwaitReply) {
            return List.of();
        }
        var where = "Step '" + stepId + "' awaitReply";
        if (!STEP_TYPES.contains(stepType)) {
            return List.of(where + ": only an HTTP_CALL or a PUBLISH_EVENT awaits a reply"
                    + ("WAIT_FOR_MESSAGE".equals(stepType) ? " (a WAIT_FOR_MESSAGE is the wait itself)." : "."));
        }
        var violations = new ArrayList<String>();
        if (blank(messageName)) {
            violations.add(where + ": a messageName is required (the message that is the reply).");
        }
        if (blank(correlationExpression)) {
            violations.add(where + ": a correlationExpression is required (the key the reply must carry,"
                    + " e.g. businessKey).");
        }
        if (hasOnEvent) {
            violations.addAll(EventTriggerRules.sourceViolations(onEventDestination, onEventType, where + ".onEvent"));
        }
        return violations;
    }

    /**
     * The wait fields declared on an {@code HTTP_CALL} / {@code PUBLISH_EVENT} itself rather than inside
     * its {@code awaitReply}, which the engine does not read there.
     */
    public static List<String> misplacedWaitFieldViolations(String stepId, String stepType,
                                                            boolean hasMessageName, boolean hasCorrelationExpression) {
        if (!STEP_TYPES.contains(stepType) || (!hasMessageName && !hasCorrelationExpression)) {
            return List.of();
        }
        return List.of("Step '" + stepId + "' declares " + (hasMessageName ? "messageName" : "correlationExpression")
                + ", which " + withArticle(stepType) + " does not read; to wait for a reply, put it inside awaitReply.");
    }

    /** "an HTTP_CALL", "a PUBLISH_EVENT". */
    static String withArticle(String stepType) {
        return ("HTTP_CALL".equals(stepType) ? "an " : "a ") + stepType;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
