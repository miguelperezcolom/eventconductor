package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.uidl.annotations.Style;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
public class Forms  extends AutoCrudOrchestrator<Form> {

    final AutoCrudAdapter<Form> formCrudAdapter;

    @Override
    public AutoCrudAdapter<Form> simpleAdapter() {
        return formCrudAdapter;
    }
}
