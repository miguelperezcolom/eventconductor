package io.mateu.workflow.application.usecases.process.operate;

import io.mateu.workflow.domain.aggregates.Variable;

import java.util.List;

/**
 * @param set         variables to write — created, or overwritten when the process has them
 * @param remove      names of variables to delete
 * @param reason      why the operator did it, for the audit trail
 * @param requestedBy the operator, as identified at the door; null when nobody was
 */
public record SetProcessVariablesCommand(String processId, List<Variable> set, List<String> remove,
                                         String reason, String requestedBy) {
}
