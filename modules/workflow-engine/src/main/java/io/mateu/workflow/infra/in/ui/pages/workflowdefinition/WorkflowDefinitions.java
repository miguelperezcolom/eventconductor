package io.mateu.workflow.infra.in.ui.pages.workflowdefinition;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowDefinitionCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class WorkflowDefinitions extends CrudOrchestrator<
        WorkflowDefinitionView,
        WorkflowDefinitionView,
        WorkflowDefinitionView,
        NoFilters,
        WorkflowDefinitionRow,
        String
        > {

    final WorkflowDefinitionCrudAdapter adapter;


    @Override
    public CrudAdapter<WorkflowDefinitionView, WorkflowDefinitionView, WorkflowDefinitionView, NoFilters, WorkflowDefinitionRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String id) {
        return id;
    }
}
