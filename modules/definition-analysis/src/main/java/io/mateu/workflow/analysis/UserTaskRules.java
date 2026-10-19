package io.mateu.workflow.analysis;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The structural rules of a USER_TASK's {@code assignment} and {@code escalation}, shared as code by
 * the engine and the Maven plugin — so a definition the build accepts is one the engine imports, and
 * the other way round. The templates inside them are checked by whoever holds a JEXL engine; these
 * are the rules that need none.
 *
 * <ul>
 *   <li>Only a USER_TASK has either: a task for a person is the only thing that is assigned.</li>
 *   <li>{@code priority} is 0–100 or a template; {@code dueDate} and {@code followUpDate} are an ISO-8601
 *       duration from the task's creation ({@code P3D}), an ISO date or date-time, or a template.</li>
 *   <li>Each escalation fires {@code after} an ISO-8601 duration (from the task's creation), and they
 *       are listed in the order they fire.</li>
 *   <li>Its {@code action} is {@code reassign} (which needs a {@code to}), {@code notify} or
 *       {@code escalateTo} (which needs the step id in {@code escalateTo}). Left out, it is read from
 *       what the entry declares: {@code escalateTo} if it names a step, {@code reassign} if it has a
 *       {@code to}, else {@code notify}.</li>
 *   <li>An {@code escalateTo} ends the task, so it is the last entry, there is one, it names another
 *       step of the definition, and it is not on a {@code forEach} step (each iteration is a task of
 *       its own, and one of them cannot route the whole step).</li>
 *   <li>Escalations are counted from the task's creation; a step whose limit follows the process's data
 *       ({@code deadline}) cannot also escalate — use {@code timeout} with it, or an escalation.</li>
 * </ul>
 */
public final class UserTaskRules {

    public static final String REASSIGN = "reassign";
    public static final String NOTIFY = "notify";
    public static final String ESCALATE_TO = "escalateTo";

    private static final Set<String> ASSIGNEE_FIELDS = Set.of("assignee", "candidateUsers", "candidateGroups");

    private UserTaskRules() {
    }

    /**
     * What is wrong with a step's {@code assignment} and {@code escalation}; empty means valid (or
     * neither declared).
     *
     * @param stepId      the step's id, for the messages
     * @param stepType    the step's type
     * @param assignment  its {@code assignment}, as a map; null when absent
     * @param escalation  its {@code escalation} entries, as maps; null when absent
     * @param forEach     whether the step runs once per element of a list
     * @param hasDeadline whether the step declares a {@code deadline}
     * @param stepIds     every step id of the definition, to resolve {@code escalateTo}
     */
    public static List<String> violations(String stepId, String stepType, Map<String, ?> assignment,
                                          List<? extends Map<String, ?>> escalation, boolean forEach,
                                          boolean hasDeadline, Collection<String> stepIds) {
        var violations = new ArrayList<String>();
        var hasEscalation = escalation != null && !escalation.isEmpty();
        if (!"USER_TASK".equals(stepType)) {
            if (assignment != null) {
                violations.add("Step '" + stepId + "' declares an assignment, which only a USER_TASK has.");
            }
            if (hasEscalation) {
                violations.add("Step '" + stepId + "' declares an escalation, which only a USER_TASK has.");
            }
            return violations;
        }
        if (assignment != null) {
            violations.addAll(assignmentViolations(assignment, "Step '" + stepId + "' assignment", true));
        }
        if (!hasEscalation) {
            return violations;
        }
        if (hasDeadline) {
            violations.add("Step '" + stepId + "' declares both a deadline and an escalation; escalations are"
                    + " counted from the task's creation — bound the step with a timeout instead.");
        }
        Duration previous = null;
        var escalateTos = 0;
        for (var i = 0; i < escalation.size(); i++) {
            var entry = escalation.get(i);
            var where = "Step '" + stepId + "' escalation[" + i + "]";
            if (entry == null) {
                violations.add(where + ": an escalation is an object.");
                continue;
            }
            var after = text(entry.get("after"));
            var duration = parseDuration(after);
            if (after == null) {
                violations.add(where + ": 'after' is required — an ISO-8601 duration from the task's creation, e.g. PT48H.");
            } else if (duration == null || duration.isZero() || duration.isNegative()) {
                violations.add(where + ": 'after' must be a positive ISO-8601 duration (e.g. PT48H, P2D), not '" + after + "'.");
            } else {
                if (previous != null && duration.compareTo(previous) < 0) {
                    violations.add(where + ": escalations are listed in the order they fire; 'after' " + after
                            + " comes before the previous entry's.");
                }
                previous = duration;
            }
            var action = actionOf(entry);
            if (action == null) {
                violations.add(where + ": action must be reassign, notify or escalateTo, not '" + entry.get("action") + "'.");
                continue;
            }
            var to = entry.get("to");
            var target = text(entry.get("escalateTo"));
            if (to != null && !REASSIGN.equals(action)) {
                violations.add(where + ": 'to' is only read by a reassign.");
            }
            if (target != null && !ESCALATE_TO.equals(action)) {
                violations.add(where + ": 'escalateTo' is only read by an escalateTo action.");
            }
            switch (action) {
                case REASSIGN -> {
                    if (!(to instanceof Map<?, ?> toMap) || ASSIGNEE_FIELDS.stream().noneMatch(field -> isSet(toMap.get(field)))) {
                        violations.add(where + ": a reassign needs a 'to' with an assignee, candidateUsers or candidateGroups.");
                    } else {
                        @SuppressWarnings("unchecked")
                        var cast = (Map<String, ?>) toMap;
                        violations.addAll(assignmentViolations(cast, where + ".to", false));
                    }
                }
                case ESCALATE_TO -> {
                    escalateTos++;
                    if (target == null) {
                        violations.add(where + ": an escalateTo needs the id of the step to route to in 'escalateTo'.");
                    } else if (target.equals(stepId)) {
                        violations.add(where + ": a step cannot escalate to itself.");
                    } else if (stepIds != null && !stepIds.contains(target)) {
                        violations.add(where + ": escalateTo references unknown step '" + target + "'.");
                    }
                    if (forEach) {
                        violations.add(where + ": a forEach USER_TASK cannot escalateTo — each iteration is a task of"
                                + " its own; reassign or notify instead, or bound the step with a timeout and onTimeoutStepId.");
                    }
                    if (i != escalation.size() - 1) {
                        violations.add(where + ": an escalateTo ends the task, so it must be the last escalation.");
                    }
                }
                default -> {
                }
            }
            var publishEvent = entry.get("publishEvent");
            if (publishEvent != null) {
                if (!(publishEvent instanceof Map<?, ?> event) || text(event.get("destination")) == null
                        || text(event.get("type")) == null) {
                    violations.add(where + ": publishEvent needs a destination and a type.");
                }
            }
        }
        if (escalateTos > 1) {
            violations.add("Step '" + stepId + "' escalation: only one entry may escalateTo.");
        }
        return violations;
    }

    /**
     * The action an escalation entry takes: the one it names, or the one implied by what it declares.
     * Null for an action that is not one of the three.
     */
    public static String actionOf(Map<String, ?> entry) {
        var declared = text(entry.get("action"));
        if (declared == null) {
            if (text(entry.get("escalateTo")) != null) {
                return ESCALATE_TO;
            }
            return entry.get("to") != null ? REASSIGN : NOTIFY;
        }
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "reassign" -> REASSIGN;
            case "notify" -> NOTIFY;
            case "escalateto", "escalate" -> ESCALATE_TO;
            default -> null;
        };
    }

    /**
     * An ISO-8601 duration ({@code PT48H}, {@code P2D}, {@code P1DT12H}), or null when the text is not
     * one. Case-insensitive, as {@link Duration#parse} is.
     */
    public static Duration parseDuration(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(text.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Whether {@code text} is an ISO date, a local date-time or an offset date-time. */
    public static boolean isIsoMoment(String text) {
        if (text == null) {
            return false;
        }
        var value = text.trim();
        for (var parser : List.<java.util.function.Consumer<String>>of(
                OffsetDateTime::parse, LocalDateTime::parse, LocalDate::parse)) {
            try {
                parser.accept(value);
                return true;
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return false;
    }

    private static List<String> assignmentViolations(Map<String, ?> assignment, String where, boolean full) {
        var violations = new ArrayList<String>();
        violations.addAll(namesViolations(assignment.get("candidateUsers"), where + ".candidateUsers"));
        violations.addAll(namesViolations(assignment.get("candidateGroups"), where + ".candidateGroups"));
        var assignee = assignment.get("assignee");
        if (assignee != null && !(assignee instanceof String)) {
            violations.add(where + ".assignee: a user name or a template, not " + assignee + ".");
        }
        if (!full) {
            return violations;
        }
        var priority = assignment.get("priority");
        if (priority instanceof Number number) {
            if (number.doubleValue() != Math.rint(number.doubleValue())
                    || number.intValue() < 0 || number.intValue() > 100) {
                violations.add(where + ".priority: a whole number from 0 to 100, not " + priority + ".");
            }
        } else if (priority instanceof String text && !TemplateSyntax.isTemplate(text)) {
            try {
                var value = Integer.parseInt(text.trim());
                if (value < 0 || value > 100) {
                    violations.add(where + ".priority: a whole number from 0 to 100, not " + text + ".");
                }
            } catch (NumberFormatException e) {
                violations.add(where + ".priority: a whole number from 0 to 100 or a template, not '" + text + "'.");
            }
        } else if (priority != null && !(priority instanceof String)) {
            violations.add(where + ".priority: a whole number from 0 to 100 or a template, not " + priority + ".");
        }
        for (var field : List.of("dueDate", "followUpDate")) {
            var value = assignment.get(field);
            if (value == null) {
                continue;
            }
            if (!(value instanceof String text)) {
                violations.add(where + "." + field + ": an ISO-8601 duration, an ISO date or a template, not " + value + ".");
                continue;
            }
            if (TemplateSyntax.isTemplate(text) || isIsoMoment(text)) {
                continue;
            }
            var duration = parseDuration(text);
            if (duration == null || duration.isNegative()) {
                violations.add(where + "." + field + ": an ISO-8601 duration from the task's creation (P3D, PT4H),"
                        + " an ISO date or date-time, or a template — not '" + text + "'.");
            }
        }
        return violations;
    }

    private static List<String> namesViolations(Object value, String where) {
        if (value == null || value instanceof String) {
            return List.of();
        }
        if (value instanceof Collection<?> names) {
            for (var name : names) {
                if (!(name instanceof String)) {
                    return List.of(where + ": a list of names or templates, but it holds " + name + ".");
                }
            }
            return List.of();
        }
        return List.of(where + ": a list of names, or a template giving one — not " + value + ".");
    }

    private static boolean isSet(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return true;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        var text = value.toString();
        return text.isBlank() ? null : text.trim();
    }
}
