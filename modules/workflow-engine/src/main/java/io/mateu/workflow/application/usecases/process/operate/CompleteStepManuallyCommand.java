package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

/**
 * @param outputVariables what the step "returned": written onto the process, as a worker's reply would be
 * @param reason          why the operator did it, for the audit trail
 * @param requestedBy     the operator, as identified at the door; null when nobody was
 */
public record CompleteStepManuallyCommand(String processId, String stepExecutionId, List<Variable> outputVariables,
                                          String reason, String requestedBy) {
}
