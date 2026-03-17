package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

public interface WorkflowDefinitionRepository extends CrudRepository<WorkflowDefinition> {
}
