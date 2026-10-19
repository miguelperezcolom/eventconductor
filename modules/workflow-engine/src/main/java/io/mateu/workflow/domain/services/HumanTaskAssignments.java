package io.mateu.workflow.domain.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mateu.workflow.analysis.UserTaskRules;
import io.mateu.workflow.domain.aggregates.Process;
import io.mateu.workflow.domain.aggregates.Step;
import io.mateu.workflow.domain.aggregates.TaskAssignment;
import io.mateu.workflow.dtos.humantasks.HumanTaskAssignment;
import io.mateu.workflow.template.Templates;
import io.mateu.workflow.time.EngineClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A USER_TASK's {@code assignment} — or an escalation's {@code to} — rendered against the process into
 * the plain values the forms engine stores: names, a priority, two instants.
 *
 * <p>Rendered in the engine because the engine is what holds the process variables and the JEXL sandbox
 * every other template in a definition runs in ({@link Templates}); the forms engine receives no
 * expression, only results. Rendered once, when the task is created: like the variables the task shows,
 * an assignment is a snapshot — a later change to the variable it read does not move the task (an
 * escalation does).
 *
 * <p>Fails loud. An assignment that cannot be rendered — a template that throws, a priority that is
 * not a number, a due date that is not a date — fails the step with the reason, as an HTTP_CALL whose
 * request cannot be rendered does: a task quietly routed to nobody, or to everybody, is worse than one
 * that visibly did not start. A template that reads an undefined variable renders empty, which is a
 * value (unassigned), not a failure.
 */
public final class HumanTaskAssignments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HumanTaskAssignments() {
    }

    /**
     * The assignment of the task {@code step} is about to create, for {@code process} (an iteration's
     * process already carries its item) — {@link HumanTaskAssignment#none()} plus the business key when
     * the step declares none.
     *
     * @throws IllegalArgumentException     when a rendered value is not what its field needs
     * @throws Templates.TemplateException when a template cannot be evaluated
     */
    public static HumanTaskAssignment resolve(Step step, Process process, String stepExecutionId,
                                              Map<String, Object> scope) {
        var businessKey = process.getBusinessKey();
        if (step.assignment() == null) {
            return new HumanTaskAssignment(null, List.of(), List.of(), null, null, null, businessKey);
        }
        var context = TemplateContext.of(process, step, stepExecutionId, scope);
        return render(step.assignment(), context, EngineClock.instant(), businessKey);
    }

    /** {@code assignment} rendered against {@code context}, its relative dates counted from {@code now}. */
    public static HumanTaskAssignment render(TaskAssignment assignment, Map<String, Object> context,
                                             Instant now, String businessKey) {
        var assignee = text(render(assignment.assignee(), context));
        var candidateUsers = names(render(assignment.candidateUsers(), context), "candidateUsers");
        var candidateGroups = names(render(assignment.candidateGroups(), context), "candidateGroups");
        var priority = priority(render(assignment.priority(), context));
        var dueDate = moment(render(assignment.dueDate(), context), now, "dueDate");
        var followUpDate = moment(render(assignment.followUpDate(), context), now, "followUpDate");
        return new HumanTaskAssignment(assignee, candidateUsers, candidateGroups, priority, dueDate,
                followUpDate, businessKey);
    }

    private static Object render(Object template, Map<String, Object> context) {
        return template == null ? null : Templates.render(template, context);
    }

    /**
     * Names from a rendered value: a list (each element's text), a JSON array in text, or a
     * comma-separated text. A list element that is itself a list — a template inside the list giving
     * several names — is flattened.
     */
    static List<String> names(Object value, String field) {
        var names = new ArrayList<String>();
        collect(value, names, field);
        return names;
    }

    private static void collect(Object value, List<String> names, String field) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.forEach(element -> collect(element, names, field));
            return;
        }
        if (value instanceof Map<?, ?>) {
            throw new IllegalArgumentException("assignment " + field + " rendered an object, not names: " + value);
        }
        var text = value.toString().trim();
        if (text.startsWith("[")) {
            try {
                collect(JSON.readValue(text, List.class), names, field);
                return;
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("assignment " + field + " is not a list of names: " + text, e);
            }
        }
        Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
    }

    static Integer priority(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        double number;
        if (value instanceof Number n) {
            number = n.doubleValue();
        } else {
            try {
                number = Double.parseDouble(value.toString().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("assignment priority is not a number: '" + value + "'");
            }
        }
        if (number != Math.rint(number) || number < HumanTaskAssignment.MIN_PRIORITY
                || number > HumanTaskAssignment.MAX_PRIORITY) {
            throw new IllegalArgumentException("assignment priority must be a whole number from 0 to 100, not " + value);
        }
        return (int) number;
    }

    /**
     * A rendered date field as an instant: an ISO-8601 duration counted from {@code now}, an offset
     * date-time, or a local date-time / date read in the engine's zone (a date alone is its start).
     * Null for an empty value.
     */
    static String moment(Object value, Instant now, String field) {
        var text = text(value);
        if (text == null) {
            return null;
        }
        var duration = UserTaskRules.parseDuration(text);
        if (duration != null) {
            return now.plus(duration).toString();
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // not with an offset
        }
        try {
            return LocalDateTime.parse(text).atZone(EngineClock.zone()).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // not a local date-time
        }
        try {
            return LocalDate.parse(text).atStartOfDay(EngineClock.zone()).toInstant().toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("assignment " + field + " is neither an ISO-8601 duration nor a date: '"
                    + text + "'");
        }
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        var text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
