package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.tier;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.CountryQueryService;
import io.mateu.workflow.controlplaneservice.application.query.TierQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.application.query.dto.TierRow;
import io.mateu.workflow.controlplaneservice.application.usecases.country.delete.DeleteCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.delete.DeleteCountryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class TierCrudAdapter implements CrudAdapter<
        TierViewModel,
        TierViewModel,
        TierViewModel,
        NoFilters,
        TierRow,
        String
        > {

    final TierViewModel viewModel;
    final DeleteCountryUseCase deleteCountryUseCase;
    final TierQueryService queryService;

    @Override
    public ListingData<TierRow> search(String searchText,
                                          NoFilters filters,
                                          Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteCountryUseCase.handle(new DeleteCountryCommand(selectedIds));
    }

    @Override
    public TierViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public TierViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public TierViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
