package io.mateu.workflow.infra.in.ui.adapters;

import io.mateu.core.infra.declarative.orchestrators.crud.AutoCrudAdapter;
import io.mateu.uidl.interfaces.CrudRepository;
import io.mateu.workflow.domain.aggregates.Step;

public class StepCrudAdapter extends AutoCrudAdapter<Step> {

    @Override
    public CrudRepository<Step> repository() {
        return null;
    }

}
