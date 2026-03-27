package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.CountryQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.CountryRow;
import io.mateu.workflow.controlplaneservice.application.usecases.country.delete.DeleteCountryCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.country.delete.DeleteCountryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Scope("prototype")
@RequiredArgsConstructor
public class CountryCrudAdapter implements CrudAdapter<
        CountryViewModel,
        CountryViewModel,
        CountryViewModel,
        NoFilters,
        CountryRow,
        String
        > {

    final CountryViewModel viewModel;
    final DeleteCountryUseCase deleteCountryUseCase;
    final CountryQueryService queryService;

    @Override
    public ListingData<CountryRow> search(String searchText,
                                          NoFilters filters,
                                          Pageable pageable) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds) {
        deleteCountryUseCase.handle(new DeleteCountryCommand(selectedIds));
    }

    @Override
    public CountryViewModel getView(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public CountryViewModel getEditor(String id) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public CountryViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
