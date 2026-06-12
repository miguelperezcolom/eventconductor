package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
public class WorkflowDefinitionCrudAdapter  extends AutoCrudAdapter<WorkflowDefinition> {

    final WorkflowDefinitionRepository repository;

    @Override
    public CrudRepository<WorkflowDefinition> repository() {
        return repository;
    }

}
