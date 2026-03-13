package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.core.infra.declarative.SimpleCrud;
import io.mateu.core.infra.declarative.SimpleCrudOrchestrator;
import io.mateu.core.infra.declarative.SimpleEntity;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.application.out.ProcessCrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends SimpleCrudOrchestrator {

    final CreateProcessForm createProcessForm;
    final ProcessCrudAdapter processCrudAdapter;


    @ListToolbarButton(confirmationRequired = false, rowsSelectedRequired = false)
    public CreateProcessForm create() {
        return createProcessForm;
    }

    @Override
    public CrudAdapter<SimpleEntity, SimpleEntity, SimpleEntity, SimpleEntity, SimpleEntity, SimpleEntity, String> adapter() {
        return processCrudAdapter;
    }
}
