package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.LanguageQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.LanguageRow;
import io.mateu.workflow.controlplaneservice.application.usecases.language.delete.DeleteLanguageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.language.delete.DeleteLanguageUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Languages")
public class LanguageCrudOrchestrator extends Crud<
        LanguageViewModel,
        LanguageViewModel,
        LanguageViewModel,
        NoFilters,
        LanguageRow,
        String
        > {

    final LanguageViewModel viewModel;
    final DeleteLanguageUseCase deleteLanguageUseCase;
    final LanguageQueryService queryService;

    @Override
    public ListingData<LanguageRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public LanguageViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public LanguageViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public LanguageViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(LanguageViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(LanguageViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteLanguageUseCase.handle(new DeleteLanguageCommand(selectedIds));
    }
}
