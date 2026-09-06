package io.mateu.workflow.application.out;

/** No workflow definition with the requested id exists, so the event that referenced it cannot run. */
public class UnknownWorkflowDefinitionException extends PoisonEventException {

    public UnknownWorkflowDefinitionException(String workflowDefinitionId) {
        super("No workflow definition with id '" + workflowDefinitionId
                + "' — the event referencing it cannot be processed");
    }
}
