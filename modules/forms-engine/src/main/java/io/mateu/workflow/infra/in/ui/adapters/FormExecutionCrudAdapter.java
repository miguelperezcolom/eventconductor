package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.FormExecutionRepository;
import io.mateu.workflow.application.out.FormRepository;
import io.mateu.workflow.domain.Form;
import io.mateu.workflow.domain.FormExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FormExecutionCrudAdapter extends AutoCrudAdapter<FormExecution> {

    final FormExecutionRepository repository;

    @Override
    public CrudRepository<FormExecution> repository() {
        return repository;
    }
}
