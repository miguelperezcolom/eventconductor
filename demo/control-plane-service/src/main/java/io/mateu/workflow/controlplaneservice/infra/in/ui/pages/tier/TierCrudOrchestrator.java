package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.TierQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierRow;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.delete.DeleteTierCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.tier.delete.DeleteTierUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Tiers")
public class TierCrudOrchestrator extends Crud<
        TierViewModel,
        TierViewModel,
        TierViewModel,
        NoFilters,
        TierRow,
        String
        > {

    final TierViewModel viewModel;
    final DeleteTierUseCase deleteTierUseCase;
    final TierQueryService queryService;

    @Override
    public ListingData<TierRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public TierViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public TierViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public TierViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(TierViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(TierViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteTierUseCase.handle(new DeleteTierCommand(selectedIds));
    }
}
