package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.environment;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.EnvironmentRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Environments")
public class EnvironmentCrudOrchestrator extends CrudOrchestrator<
        EnvironmentViewModel,
        EnvironmentViewModel,
        EnvironmentViewModel,
        NoFilters,
        EnvironmentRow,
        String
        > {

    final EnvironmentCrudAdapter adapter;

    @Override
    public CrudAdapter<EnvironmentViewModel,
            EnvironmentViewModel, EnvironmentViewModel,
            NoFilters, EnvironmentRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
