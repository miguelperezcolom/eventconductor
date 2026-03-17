package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowDefinitionCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class WorkflowDefinitions extends AutoCrudOrchestrator<WorkflowDefinition> {

    final WorkflowDefinitionCrudAdapter adapter;

    @Override
    public AutoCrudAdapter<WorkflowDefinition> simpleAdapter() {
        return adapter;
    }
}
