package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionRow;
import io.mateu.workflow.infra.in.ui.pages.workflowdefinition.WorkflowDefinitionView;

public interface WorkflowDefinitionCrudAdapter extends CrudAdapter<WorkflowDefinitionView, WorkflowDefinitionView, WorkflowDefinitionView, NoFilters, WorkflowDefinitionRow, String> {
}
