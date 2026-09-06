package io.mateu.workflow.application.out;

/** No process with the requested id exists, so the event that referenced it cannot be processed. */
public class UnknownProcessException extends PoisonEventException {

    public UnknownProcessException(String processId) {
        super("No process with id '" + processId
                + "' — the event referencing it cannot be processed");
    }
}
