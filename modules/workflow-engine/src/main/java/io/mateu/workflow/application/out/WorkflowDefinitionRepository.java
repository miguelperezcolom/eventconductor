package io.mateu.workflow.application.out;

import io.mateu.uidl.interfaces.CrudStore;
import io.mateu.workflow.domain.aggregates.StepExecution;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;

public interface WorkflowDefinitionRepository extends CrudStore<WorkflowDefinition> {
}
