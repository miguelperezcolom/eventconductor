package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.assetversion;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.out.AssetVersionRepository;
import io.mateu.workflow.controlplaneservice.application.query.AssetVersionQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.AssetVersionRow;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.delete.DeleteAssetVersionCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.assetversion.delete.DeleteAssetVersionUseCase;
import io.mateu.workflow.controlplaneservice.domain.aggregates.assetversion.vo.AssetVersionId;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class AssetVersionCrudAdapter implements CrudAdapter<
AssetVersionViewModel,
AssetVersionViewModel,
AssetVersionViewModel,
NoFilters,
AssetVersionRow,
String
> {

final AssetVersionViewModel viewModel;
final DeleteAssetVersionUseCase deleteAssetVersionUseCase;
final AssetVersionQueryService queryService;

@Override
public ListingData<AssetVersionRow> search(String searchText,
    NoFilters filters,
    Pageable pageable) {
    return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteAssetVersionUseCase.handle(new DeleteAssetVersionCommand(selectedIds));
        }

        @Override
        public AssetVersionViewModel getView(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public AssetVersionViewModel getEditor(String id) {
        return viewModel.load(queryService
        .getById(id)
        .orElseThrow());
        }

        @Override
        public AssetVersionViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
        }
        }
