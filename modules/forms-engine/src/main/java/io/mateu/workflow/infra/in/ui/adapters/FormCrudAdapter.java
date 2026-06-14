package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormCrudAdapter extends AutoCrudAdapter<Form> {

    final FormRepository repository;

    @Override
    public CrudRepository<Form> repository() {
        return repository;
    }
}
