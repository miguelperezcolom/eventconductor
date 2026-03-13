package io.mateu.workflow.infra.in.ui.pages.workflowdefinition;

import io.mateu.uidl.interfaces.CrudCreationForm;
import io.mateu.uidl.interfaces.CrudEditorForm;
import io.mateu.uidl.interfaces.HttpRequest;

public record WorkflowDefinitionView() implements
        CrudEditorForm<String>,
        CrudCreationForm<String> {
    @Override
    public String create(HttpRequest httpRequest) {
        return "";
    }

    @Override
    public void save(HttpRequest httpRequest) {

    }

    @Override
    public String id() {
        return "";
    }
}
