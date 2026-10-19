package io.mateu.workflow.dtos.events.integration;

import java.time.Instant;
import java.util.List;

/**
 * A human task — a form someone has to fill in — changed: it was created, assigned, claimed,
 * released, completed, cancelled or escalated. The forms engine publishes it on its
 * {@code humanTasks} binding (topic {@code human-tasks} in the standalone app) for whoever keeps
 * people's work in one place — an inbox, a notification centre, a workflow started by it — as a
 * CloudEvent ({@code ce_type} {@code io.eventconductor.task.<event>}, see {@link #cloudEventType()}).
 * The engine does not read it.
 *
 * @param taskId          the task (the form execution) — the same id on every change of one task
 * @param status          {@code PENDING}, {@code ASSIGNED}, {@code COMPLETED} or {@code CANCELLED}
 * @param requiredRoles   who may work on it, as its form requires when the task changes; empty means
 *                        anyone who may use the forms. A snapshot, for routing: whether a person may
 *                        actually complete it is still decided by the forms engine when they try
 * @param userId          who holds it (its assignee) or completed it, when someone does
 * @param event           what happened: {@code created}, {@code assigned}, {@code claimed},
 *                        {@code released}, {@code completed}, {@code cancelled} or {@code escalated}
 * @param candidateUsers  who may claim it
 * @param candidateGroups the groups whose members may claim it
 * @param priority        0–100
 * @param dueDate         when it is due, or null
 * @param escalationLevel how many of its escalations have fired
 * @param businessKey     its process's business key, when known
 */
public record HumanTaskChanged(String taskId,
                               String formId,
                               String formName,
                               String processId,
                               String stepId,
                               String status,
                               List<String> requiredRoles,
                               String userId,
                               Instant at,
                               String event,
                               List<String> candidateUsers,
                               List<String> candidateGroups,
                               Integer priority,
                               Instant dueDate,
                               Integer escalationLevel,
                               String businessKey) {

    public static final String CREATED = "created";
    public static final String ASSIGNED = "assigned";
    public static final String CLAIMED = "claimed";
    public static final String RELEASED = "released";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";
    public static final String ESCALATED = "escalated";

    /** The CloudEvents type prefix of every task lifecycle event. */
    public static final String TYPE_PREFIX = "io.eventconductor.task.";

    /** The shape before the lifecycle event and the assignment were carried. */
    public HumanTaskChanged(String taskId, String formId, String formName, String processId, String stepId,
                            String status, List<String> requiredRoles, String userId, Instant at) {
        this(taskId, formId, formName, processId, stepId, status, requiredRoles, userId, at,
                null, List.of(), List.of(), null, null, null, null);
    }

    /** {@code io.eventconductor.task.<event>} — what an event trigger matches on. */
    public String cloudEventType() {
        return TYPE_PREFIX + (event == null ? "changed" : event);
    }
}
