package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.domain.aggregates.Process;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessCrudAdapter extends AutoCrudAdapter<Process> {

    final ProcessRepository repository;

    @Override
    public CrudRepository<Process> repository() {
        return repository;
    }

}
