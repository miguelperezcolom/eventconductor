package io.mateu.workflow.application.services;

import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskQuery;

/**
 * Who may see, claim, complete, release or reassign a human task, from what the task names — its
 * assignee, candidate users and candidate groups — and who is asking. The rule every listing, page,
 * REST call and MCP tool applies, written once; the JPA inbox query is its translation to SQL and is
 * tested against it.
 *
 * <ul>
 *   <li><b>See</b>: a task admin sees every task; anyone sees what they hold; a task nobody holds is
 *       seen by its candidates (by name, or through a group) — or by everybody, when it names nobody
 *       at all (how every task behaved before assignment existed). A task somebody else holds is
 *       theirs, and not listed for anyone else.</li>
 *   <li><b>Claim</b>: an open task nobody holds, by someone who may see it.</li>
 *   <li><b>Complete</b>: by whoever holds it.</li>
 *   <li><b>Release</b>: by whoever holds it, or a task admin.</li>
 *   <li><b>Reassign</b>: a task admin.</li>
 * </ul>
 *
 * These are the assignment rules only. The form's {@code requiredRoles}/{@code requiredScopes} are a
 * separate gate ({@link TaskAuthorization}) and both must pass — an admin included.
 */
public final class TaskVisibility {

    private TaskVisibility() {
    }

    public static boolean canSee(FormExecution task, TaskCaller caller) {
        if (caller.admin() || holds(task, caller)) {
            return true;
        }
        return !task.isAssigned() && (!task.isRestricted() || isCandidate(task, caller));
    }

    public static boolean canClaim(FormExecution task, TaskCaller caller) {
        return caller.known() && task.isOpen() && !task.isAssigned() && canSee(task, caller);
    }

    public static boolean canComplete(FormExecution task, TaskCaller caller) {
        return task.isOpen() && holds(task, caller);
    }

    public static boolean canRelease(FormExecution task, TaskCaller caller) {
        return task.isOpen() && task.isAssigned() && (holds(task, caller) || caller.admin());
    }

    public static boolean canReassign(FormExecution task, TaskCaller caller) {
        return task.isOpen() && caller.admin();
    }

    /** Whether the task is offered to the caller: by name, or through one of their groups. */
    public static boolean isCandidate(FormExecution task, TaskCaller caller) {
        return (caller.known() && task.candidateUsers().contains(caller.subject())) || caller.inAnyOf(task.candidateGroups());
    }

    /** Whether the caller holds the task. */
    public static boolean holds(FormExecution task, TaskCaller caller) {
        return caller.known() && caller.subject().equals(task.userId());
    }

    /** Whether the task is in the query's scope for the caller (on top of {@link #canSee}). */
    public static boolean inScope(FormExecution task, TaskCaller caller, TaskQuery.Scope scope) {
        return switch (scope) {
            case ALL -> canSee(task, caller);
            case MINE -> holds(task, caller);
            case CANDIDATE -> !task.isAssigned() && isCandidate(task, caller);
            case GROUPS -> !task.isAssigned() && caller.inAnyOf(task.candidateGroups());
        };
    }
}
