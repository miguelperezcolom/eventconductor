package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.GenericCrud;
import io.mateu.uidl.interfaces.Repository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Forms  extends GenericCrud<Form> {

    final FormRepository formRepository;

    @Override
    public Repository<Form, String> repository() {
        return null;
    }
}
