package io.mateu.workflow.infra.in.ui.pages;

import io.mateu.workflow.application.out.ProcessCrudAdapter;
import io.mateu.workflow.domain.Process;
import io.mateu.core.infra.declarative.GenericCrud;
import io.mateu.uidl.interfaces.CrudAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class Processes extends GenericCrud<Process> {

    final ProcessCrudAdapter processCrudAdapter;

    @Override
    public CrudAdapter<Process, String> adapter() {
        return (CrudAdapter<Process, String>) processCrudAdapter;
    }
}
