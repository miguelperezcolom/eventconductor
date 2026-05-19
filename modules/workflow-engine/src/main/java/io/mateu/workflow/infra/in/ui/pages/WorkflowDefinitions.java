package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Toolbar;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.usecases.workingcopy.CreateWorkingCopyUseCase;
import io.mateu.workflow.application.usecases.workingcopy.PromoteWorkingCopyUseCase;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import io.mateu.workflow.infra.in.ui.adapters.WorkflowDefinitionCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Action(id = "action-on-view-graphEditor")
public class WorkflowDefinitions extends AutoCrudOrchestrator<WorkflowDefinition> {

    final WorkflowDefinitionEditor graphEditor;
    final WorkflowDefinitionCrudAdapter adapter;
    final CreateWorkingCopyUseCase createWorkingCopyUseCase;
    final PromoteWorkingCopyUseCase promoteWorkingCopyUseCase;

    @Override
    public AutoCrudAdapter<WorkflowDefinition> simpleAdapter() {
        return adapter;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    @ListToolbarButton
    public void importFromGithub() throws Exception {
        throw new Exception("No configured");
    }

    @ViewToolbarButton
    public WorkflowDefinitionEditor graphEditor(HttpRequest httpRequest) {
        return graphEditor.load(httpRequest.getComponentState(WorkflowDefinition.class).id());
    }

    @ViewToolbarButton
    public void createWorkingCopy(HttpRequest httpRequest) {
        var definition = httpRequest.getComponentState(WorkflowDefinition.class);
        createWorkingCopyUseCase.handle(definition.id());
    }

    @ViewToolbarButton
    public void promoteToProduction(HttpRequest httpRequest) {
        var definition = httpRequest.getComponentState(WorkflowDefinition.class);
        promoteWorkingCopyUseCase.handle(definition.id());
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if ("action-on-view-graphEditor".equals(actionId)) {
            return graphEditor(httpRequest);
        }
        return super.handleAction(actionId, httpRequest);
    }
}
