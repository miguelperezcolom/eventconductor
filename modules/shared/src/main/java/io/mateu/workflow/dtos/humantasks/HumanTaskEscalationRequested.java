package io.mateu.workflow.dtos.humantasks;

import io.mateu.workflow.ddd.DomainEvent;

/**
 * The engine escalated a human task: its {@code escalation} entry number {@code level} came due.
 * Sent to the worker side of a USER_TASK — the forms engine — on the topic the task was dispatched to.
 *
 * <p>The engine owns the clock (escalations are timers, and the test kit moves its time), the forms
 * engine owns the task: this event is how the one tells the other. The forms engine applies it once —
 * a task already escalated to this level ignores it — so a redelivery is harmless.
 *
 * @param taskExecutionId the USER_TASK's step execution — the forms engine's {@code stepExecutionId}
 * @param level           1-based position of the escalation in the step's list
 * @param action          {@code reassign}, {@code notify} or {@code escalateTo}
 * @param reassignTo      for {@code reassign}, who the task is for now (replacing its assignee and
 *                        candidates); null otherwise
 * @param escalateTo      for {@code escalateTo}, the step the process was routed to; null otherwise
 * @param at              when it was escalated (engine time), an ISO-8601 instant
 */
public record HumanTaskEscalationRequested(String taskExecutionId,
                                           String processId,
                                           String stepId,
                                           int level,
                                           String action,
                                           HumanTaskAssignment reassignTo,
                                           String escalateTo,
                                           String at) implements DomainEvent {

    public static final String REASSIGN = "reassign";
    public static final String NOTIFY = "notify";
    public static final String ESCALATE_TO = "escalateTo";

    @Override
    public String partitionKey() {
        return processId;
    }
}
