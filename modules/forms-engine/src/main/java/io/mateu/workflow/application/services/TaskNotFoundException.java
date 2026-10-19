package io.mateu.workflow.application.services;

/**
 * No task with this id — or none this caller may see, which is answered the same way on purpose: a
 * task id travels in links and logs, and "it exists but is not yours" would tell its holder's
 * business to whoever pasted it.
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("No task " + taskId);
    }
}
