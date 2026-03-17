package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.Form;
import org.springframework.stereotype.Service;

@Service
public class FormCrudAdapter extends AutoCrudAdapter<Form> {

    @Override
    public CrudRepository<Form> repository() {
        return null;
    }
}
