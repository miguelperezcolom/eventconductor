package io.mateu.workflow.dtos.events.integration;

import io.mateu.workflow.ddd.DomainEvent;
import io.mateu.workflow.dtos.Variable;
import io.mateu.workflow.security.AuthorizationContext;

import java.util.List;

/**
 * @param caller who asked for this process, as the identity and granted scopes the edge that
 *               accepted the request had already validated — never the token itself, which by the
 *               time a step runs has long expired (see {@link AuthorizationContext}). It is what the
 *               definition's {@code requiredScopes}/{@code requiredRoles} are checked against.
 *               {@code null} for a producer that predates the field, and for anything the engine
 *               starts for itself, where {@link AuthorizationContext#SYSTEM} says so explicitly.
 */
public record ProcessCreationRequested(String workflowDefinitionId, String businessKey, List<Variable> variables,
                                       String parentStepExecutionId,
                                       AuthorizationContext caller) implements DomainEvent {

    /** Top-level process creation (no parent step execution). */
    public ProcessCreationRequested(String workflowDefinitionId, String businessKey, List<Variable> variables) {
        this(workflowDefinitionId, businessKey, variables, null, null);
    }

    /** The shape before flow authorization existed: a creation that names nobody. */
    public ProcessCreationRequested(String workflowDefinitionId, String businessKey, List<Variable> variables,
                                    String parentStepExecutionId) {
        this(workflowDefinitionId, businessKey, variables, parentStepExecutionId, null);
    }

    @Override
    public String partitionKey() {
        return businessKey;
    }
}
