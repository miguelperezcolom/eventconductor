package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.assetversion;

import io.mateu.core.infra.declarative.CrudOrchestrator;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetVersionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("AssetVersions")
public class AssetVersionCrudOrchestrator extends CrudOrchestrator<
AssetVersionViewModel,
AssetVersionViewModel,
AssetVersionViewModel,
NoFilters,
AssetVersionRow,
String
> {

final AssetVersionCrudAdapter adapter;

@Override
public CrudAdapter<AssetVersionViewModel,
AssetVersionViewModel, AssetVersionViewModel,
NoFilters, AssetVersionRow, String> adapter() {
return adapter;
}

@Override
public String toId(String s) {
return s;
}
}
