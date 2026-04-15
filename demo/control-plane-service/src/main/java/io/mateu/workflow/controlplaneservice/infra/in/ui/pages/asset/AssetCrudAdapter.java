package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.asset;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
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
@Scope("prototype")
@RequiredArgsConstructor
public class AssetCrudAdapter implements CrudAdapter<
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
    public ListingData<AssetRow> search(String searchText,
                                        NoFilters filters,
                                        Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteAssetUseCase.handle(new DeleteAssetCommand(selectedIds));
    }

    @Override
    public AssetViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public AssetViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public AssetViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
