package io.mateu.workflow.domain.aggregates;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who a USER_TASK is for and how urgent it is, as the definition declares it: the step's
 * {@code assignment} block, or an escalation's {@code to}. Every value may be a {@code ${…}} template
 * over the process variables (an iteration's item too), rendered when the task is created — see
 * {@link io.mateu.workflow.domain.services.HumanTaskAssignments}.
 *
 * @param assignee        the user the task is assigned to
 * @param candidateUsers  users who may claim it: a list of names or templates, or one template giving a list
 * @param candidateGroups groups whose members may claim it, the same way
 * @param priority        0–100 (default 50), or a template
 * @param dueDate         an ISO-8601 duration from the task's creation ({@code P3D}), an ISO date or
 *                        date-time, or a template giving either
 * @param followUpDate    the same, for when someone should look at it again
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAssignment(
        String assignee,
        Object candidateUsers,
        Object candidateGroups,
        Object priority,
        String dueDate,
        String followUpDate
) {

    /** As a structured template — the shape {@code Templates.render} and the analysis rules read. */
    public Map<String, Object> asMap() {
        var map = new LinkedHashMap<String, Object>();
        put(map, "assignee", assignee);
        put(map, "candidateUsers", candidateUsers);
        put(map, "candidateGroups", candidateGroups);
        put(map, "priority", priority);
        put(map, "dueDate", dueDate);
        put(map, "followUpDate", followUpDate);
        return map;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
