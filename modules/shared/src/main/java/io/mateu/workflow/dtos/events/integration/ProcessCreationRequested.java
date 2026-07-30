package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;

import java.util.List;

public record ProcessCreationRequested(String workflowDefinitionId, String businessKey, List<Variable> variables,
                                       String parentStepExecutionId) implements DomainEvent {

    /** Top-level process creation (no parent step execution). */
    public ProcessCreationRequested(String workflowDefinitionId, String businessKey, List<Variable> variables) {
        this(workflowDefinitionId, businessKey, variables, null);
    }
}
