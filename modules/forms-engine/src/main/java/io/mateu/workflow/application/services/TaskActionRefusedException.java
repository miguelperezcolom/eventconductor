package io.mateu.workflow.application.services;

/**
 * Someone asked to do something to a task they may see but may not do that to — claim one somebody
 * already holds, complete one they do not hold, reassign one without being a task admin. The REST API
 * answers it with 409 Conflict (403 for a reassign by a non-admin).
 */
public class TaskActionRefusedException extends RuntimeException {

    private final boolean forbidden;

    public TaskActionRefusedException(String message, boolean forbidden) {
        super(message);
        this.forbidden = forbidden;
    }

    /** Whether the refusal is about who is asking (403), rather than the task's state (409). */
    public boolean forbidden() {
        return forbidden;
    }
}
