package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.core.infra.declarative.AutoCrudOrchestrator;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.workflow.infra.in.ui.adapters.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import io.mateu.workflow.domain.aggregates.Process;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends AutoCrudOrchestrator<Process> {

    final CreateProcessForm createProcessForm;
    final ProcessCrudAdapter processCrudAdapter;

    @ListToolbarButton(confirmationRequired = false, rowsSelectedRequired = false)
    public CreateProcessForm create() {
        return createProcessForm;
    }
    @Override
    public AutoCrudAdapter<Process> simpleAdapter() {
        return processCrudAdapter;
    }
}
