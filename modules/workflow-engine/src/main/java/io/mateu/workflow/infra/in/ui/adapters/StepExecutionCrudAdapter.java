package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.StepExecution;

public class StepExecutionCrudAdapter extends AutoCrudAdapter<StepExecution> {
    @Override
    public CrudRepository<StepExecution> repository() {
        return null;
    }
}
