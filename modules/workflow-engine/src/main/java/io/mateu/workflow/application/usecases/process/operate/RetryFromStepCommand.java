package io.mateu.workflow.application.usecases.process.operate;

/**
 * @param stepId      the definition's step (not a step execution) to run the process again from
 * @param reason      why the operator did it, for the audit trail
 * @param requestedBy the operator, as identified at the door; null when nobody was
 */
public record RetryFromStepCommand(String processId, String stepId, String reason, String requestedBy) {
}
