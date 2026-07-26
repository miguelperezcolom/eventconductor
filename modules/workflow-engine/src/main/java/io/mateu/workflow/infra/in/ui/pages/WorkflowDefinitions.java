package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.StyleConstants;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.WorkflowDefinitionRepository;
import io.mateu.workflow.domain.aggregates.WorkflowDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Service
@Scope("prototype")
@RequiredArgsConstructor
public class WorkflowDefinitions extends AutoCrud<WorkflowDefinition> {

    final WorkflowDefinitionDetailView detailView;
    final WorkflowDefinitionRepository repository;

    @Override
    public CrudRepository<WorkflowDefinition> store() {
        return repository;
    }

    @Override
    public String getStyleForView() {
        return StyleConstants.FULL_WIDTH_WITH_PADDING;
    }

    // Selecting a row (the "view" action) shows a read-only detail: summarised fields, the list of
    // steps and an inline read-only graph. The "edit" action keeps rendering the WorkflowDefinition
    // editor form.
    @Override
    public Object view(String id, HttpRequest httpRequest) {
        return detailView.load(id);
    }

    @Override
    public Class<?> viewClass() {
        return WorkflowDefinitionDetailView.class;
    }

    @ListToolbarButton
    public void importFromGithub() throws Exception {
        throw new Exception("No configured");
    }

}
