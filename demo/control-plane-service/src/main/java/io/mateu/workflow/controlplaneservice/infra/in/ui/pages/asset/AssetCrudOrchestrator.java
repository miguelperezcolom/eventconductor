package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Assets")
public class AssetCrudOrchestrator extends CrudOrchestrator<
        AssetViewModel,
        AssetViewModel,
        AssetViewModel,
        NoFilters,
        AssetRow,
        String
        > {

    final AssetCrudAdapter adapter;

    @Override
    public CrudAdapter<AssetViewModel,
            AssetViewModel, AssetViewModel,
            NoFilters, AssetRow, String> adapter() {
        return adapter;
    }

    @Override
    public String toId(String s) {
        return s;
    }
}
