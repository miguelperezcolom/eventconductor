package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.country;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
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
@RequiredArgsConstructor
@Scope("prototype")
@Title("Countries")
public class CountryCrudOrchestrator extends Crud<
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
    public ListingData<CountryRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public CountryViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public CountryViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public CountryViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(CountryViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(CountryViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteCountryUseCase.handle(new DeleteCountryCommand(selectedIds));
    }
}
