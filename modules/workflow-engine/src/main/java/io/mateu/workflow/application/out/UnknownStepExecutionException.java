package io.mateu.workflow.application.out;

/** No step execution with the requested id exists, so a worker status update cannot be applied. */
public class UnknownStepExecutionException extends PoisonEventException {

    public UnknownStepExecutionException(String stepExecutionId) {
        super("No step execution with id '" + stepExecutionId
                + "' — the status update referencing it cannot be applied");
    }
}
