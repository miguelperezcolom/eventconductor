package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.AllArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "workflow.persistence", havingValue = "jpa", matchIfMissing = true)
@Service
@AllArgsConstructor
public class WorkflowDefinitionCrudAdapter  extends AutoCrudAdapter<WorkflowDefinition> {

    final WorkflowDefinitionRepository repository;

    @Override
    public CrudRepository<WorkflowDefinition> repository() {
        return repository;
    }

}
