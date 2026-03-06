package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.FormCrudAdapter;
import io.mateu.workflow.domain.Form;
import io.mateu.core.infra.declarative.GenericCrud;
import io.mateu.uidl.annotations.Style;
import io.mateu.uidl.interfaces.CrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
@Style("width: 100%;")
public class Forms  extends GenericCrud<Form> {

    final FormCrudAdapter formRepository;

    @Override
    public CrudAdapter<Form, String> adapter() {
        return (CrudAdapter<Form, String>) formRepository;
    }
}
