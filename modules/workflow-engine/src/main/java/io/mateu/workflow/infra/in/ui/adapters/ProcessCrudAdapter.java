package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Process;

public class ProcessCrudAdapter extends AutoCrudAdapter<Process> {

    @Override
    public CrudRepository<Process> repository() {
        return null;
    }

}
