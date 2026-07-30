package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.AssetQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetRow;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.delete.DeleteAssetCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.asset.delete.DeleteAssetUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Assets")
public class AssetCrudOrchestrator extends Crud<
        AssetViewModel,
        AssetViewModel,
        AssetViewModel,
        NoFilters,
        AssetRow,
        String
        > {

    final AssetViewModel viewModel;
    final DeleteAssetUseCase deleteAssetUseCase;
    final AssetQueryService queryService;

    @Override
    public ListingData<AssetRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public AssetViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public AssetViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public AssetViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(AssetViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(AssetViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteAssetUseCase.handle(new DeleteAssetCommand(selectedIds));
    }
}
