package io.mateu.workflow.application.out;

import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

import java.util.List;
import java.util.Optional;

/**
 * The recorded versions of the workflow definitions — what a migration can move a process to besides the
 * definition as it is now. Present with {@code workflow.persistence=jpa} (the version history is durable
 * state); absent otherwise, and then only the current version is a migration target.
 */
public interface WorkflowDefinitionHistory {

    /** The definition as it was recorded at {@code version}, stamped with it. */
    Optional<WorkflowDefinition> find(String definitionId, int version);

    /** Every recorded version number of the definition, newest first. */
    List<Integer> versions(String definitionId);
}
