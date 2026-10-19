package io.mateu.workflow.application.services;

import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.FormExecution;
import io.mateu.workflow.domain.TaskCaller;
import io.mateu.workflow.domain.TaskPage;
import io.mateu.workflow.domain.TaskQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * A person's tasks: what the forms UI, the REST API ({@code /forms/api/tasks}) and the MCP tools list,
 * and the one place that combines the two rules a task listing must apply — what the task names
 * ({@link TaskVisibility}) and what its form requires ({@link TaskAuthorization}).
 */
@Service
@RequiredArgsConstructor
public class TaskInbox {

    final FormExecutionRepository formExecutionRepository;
    final FormRepository formRepository;
    final TaskAuthorization taskAuthorization;

    public TaskPage search(TaskQuery query, TaskCaller caller) {
        return formExecutionRepository.search(query, caller, permittedFormIds());
    }

    /** How many tasks the query matches for the caller. */
    public long count(TaskQuery query, TaskCaller caller) {
        return search(query.withPage(0, 1), caller).total();
    }

    /**
     * The task, if the caller may see it — by what it names and by what its form requires.
     *
     * @throws TaskNotFoundException when there is none, or none they may see
     */
    public FormExecution get(String taskId, TaskCaller caller) {
        var task = formExecutionRepository.findById(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        var permitted = permittedFormIds();
        if (!TaskVisibility.canSee(task, caller) || (permitted != null && !permitted.contains(task.formId()))) {
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    /** The forms the caller may work on, or null when flow authorization is off (no restriction). */
    Collection<String> permittedFormIds() {
        return taskAuthorization.enabled() ? taskAuthorization.permittedFormIds() : null;
    }
}
