package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrud;
import io.mateu.uidl.annotations.Action;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.annotations.ViewToolbarButton;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
@Action(id = "action-on-view-graphEditor")
public class Forms extends AutoCrud<Form> {

    final FormEditor formEditor;
    final FormRepository formRepository;

    @Override
    public CrudRepository<Form> store() {
        return formRepository;
    }

    @ViewToolbarButton
    public FormEditor graphEditor(HttpRequest httpRequest) {
        return formEditor.load(httpRequest.getComponentState(Form.class).id());
    }

    @Override
    public Object handleAction(String actionId, HttpRequest httpRequest) {
        if ("action-on-view-graphEditor".equals(actionId)) {
            return graphEditor(httpRequest);
        }
        return super.handleAction(actionId, httpRequest);
    }
}
