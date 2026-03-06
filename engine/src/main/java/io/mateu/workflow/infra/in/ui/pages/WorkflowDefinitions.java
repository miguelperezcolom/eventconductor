package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.WorkflowDefinitionCrudAdapter;
import io.mateu.workflow.domain.WorkflowDefinition;
import io.mateu.core.infra.declarative.GenericCrud;
import io.mateu.uidl.interfaces.CrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class WorkflowDefinitions extends GenericCrud<WorkflowDefinition> {

    final WorkflowDefinitionCrudAdapter workflowDefinitionRepository;

    @Override
    public CrudAdapter<WorkflowDefinition, String> adapter() {
        return (CrudAdapter<WorkflowDefinition, String>) workflowDefinitionRepository;
    }
}
