package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskPage;
import io.mateu.workflow.domain.TaskQuery;

import java.util.Collection;
import java.util.List;

public interface FormExecutionRepository extends CrudStore<FormExecution> {

    /** The tasks a USER_TASK's step execution created — one per dispatch of it. */
    default List<FormExecution> findByStepExecutionId(String stepExecutionId) {
        return findAll().stream()
                .filter(task -> stepExecutionId != null && stepExecutionId.equals(task.stepExecutionId()))
                .toList();
    }

    /**
     * One page of the tasks {@code query} asks for that {@code caller} may see (see
     * {@code TaskVisibility}), narrowed to {@code permittedFormIds} when that is not null — the forms
     * the caller's roles and scopes allow (null: no such restriction; empty: none at all).
     */
    TaskPage search(TaskQuery query, TaskCaller caller, Collection<String> permittedFormIds);

    /**
     * Gives the task to {@code userId} if nobody holds it — atomically: of two people claiming the same
     * task at once, one gets it and the other is told no. The task becomes ASSIGNED.
     *
     * @return whether this call claimed it
     */
    boolean claim(String taskId, String userId);
}
