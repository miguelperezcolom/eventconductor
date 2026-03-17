package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoListAdapter;
import io.mateu.core.infra.declarative.AutoListOrchestrator;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.application.out.ProcessRepository;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.domain.aggregates.Process;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends AutoCrudAdapter<Process> {

    final CreateProcessForm createProcessForm;
    final ProcessRepository processRepository;


    @ListToolbarButton(confirmationRequired = false, rowsSelectedRequired = false)
    public CreateProcessForm create() {
        return createProcessForm;
    }

    @Override
    public CrudRepository<Process> repository() {
        return processRepository;
    }
}
