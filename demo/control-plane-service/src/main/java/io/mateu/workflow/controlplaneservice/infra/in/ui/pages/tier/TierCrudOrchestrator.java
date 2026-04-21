package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Tiers")
public class TierCrudOrchestrator extends CrudOrchestrator<
        TierViewModel,
        TierViewModel,
        TierViewModel,
        NoFilters,
        TierRow,
        String
        > {

    final TierCrudAdapter adapter;

    @Override
    public CrudAdapter<TierViewModel,
            TierViewModel, TierViewModel,
            NoFilters, TierRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
