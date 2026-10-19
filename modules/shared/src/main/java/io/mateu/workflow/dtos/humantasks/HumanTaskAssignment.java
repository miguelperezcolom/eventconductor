package io.mateu.workflow.dtos.humantasks;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Who a human task is for, and how urgent it is — a USER_TASK's {@code assignment} as the engine
 * evaluated it against the process when the task was created, or an escalation's {@code to} when it
 * reassigns one.
 *
 * <p>The engine renders the definition's templates (the only place that has the process variables and
 * the JEXL sandbox), so what crosses to the forms engine is plain values: names, a number, two moments.
 * It travels as JSON in the task's {@value #VARIABLE} variable — a variable rather than a new field on
 * {@code TaskExecutionRequested}, so a worker or a forms engine that does not know it simply ignores it.
 *
 * @param assignee        the one person the task is assigned to; null when nobody is
 * @param candidateUsers  people who may claim it
 * @param candidateGroups groups whose members may claim it (a group is a role of the caller)
 * @param priority        0–100, higher is more urgent; null means the default ({@value #DEFAULT_PRIORITY})
 * @param dueDate         when it is due, an ISO-8601 instant; null when it has no due date
 * @param followUpDate    when someone should look at it again, an ISO-8601 instant; null when none
 * @param businessKey     the process's business key, for finding a task by it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HumanTaskAssignment(String assignee,
                                  List<String> candidateUsers,
                                  List<String> candidateGroups,
                                  Integer priority,
                                  String dueDate,
                                  String followUpDate,
                                  String businessKey) {

    /** The task variable the assignment travels in. Internal: the forms engine does not show it. */
    public static final String VARIABLE = "__task";

    /** The priority of a task that declares none — the middle of the scale, as in BPMN engines. */
    public static final int DEFAULT_PRIORITY = 50;

    public static final int MIN_PRIORITY = 0;

    public static final int MAX_PRIORITY = 100;

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public HumanTaskAssignment {
        assignee = blank(assignee) ? null : assignee.trim();
        candidateUsers = names(candidateUsers);
        candidateGroups = names(candidateGroups);
    }

    /** Nobody in particular: anyone who may use the forms may take it. */
    public static HumanTaskAssignment none() {
        return new HumanTaskAssignment(null, List.of(), List.of(), null, null, null, null);
    }

    /**
     * Whether the task names who it is for. A task that names nobody is open to whoever may work on
     * its form, which is how every task behaved before assignment existed.
     */
    @JsonIgnore
    public boolean restricted() {
        return assignee != null || !candidateUsers.isEmpty() || !candidateGroups.isEmpty();
    }

    /** The priority, with the default for none. */
    @JsonIgnore
    public int priorityOrDefault() {
        return priority == null ? DEFAULT_PRIORITY : priority;
    }

    /** This assignment with another person/candidates, keeping its priority, dates and business key. */
    public HumanTaskAssignment reassignedTo(HumanTaskAssignment to) {
        return new HumanTaskAssignment(to.assignee(), to.candidateUsers(), to.candidateGroups(),
                priority, dueDate, followUpDate, businessKey);
    }

    /** As the JSON the variable carries. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A task assignment is always representable as JSON", e);
        }
    }

    /** Read back from the variable; null for a blank or unreadable value. */
    public static HumanTaskAssignment fromJson(String json) {
        if (blank(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, HumanTaskAssignment.class);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Whether a task variable is one of the engine's own ({@code __task}, {@code __escalations} …), not the process's. */
    public static boolean isInternalVariable(String name) {
        return name != null && name.startsWith("__");
    }

    private static List<String> names(Collection<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
