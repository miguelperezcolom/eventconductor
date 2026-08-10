package io.mateu.workflow.application.usecases.process.inject;

/**
 * A DYNAMIC step's request to add steps to its running process.
 *
 * @param taskExecutionId the DYNAMIC step execution doing the injecting (and the idempotency key)
 * @param stepsJson       a JSON array of step objects, in the workflow-definition step schema
 */
public record InjectStepsCommand(String taskExecutionId, String stepsJson) {
}
