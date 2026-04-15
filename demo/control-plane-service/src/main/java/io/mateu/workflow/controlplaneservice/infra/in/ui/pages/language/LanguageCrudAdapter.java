package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.language;

import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.Pageable;
import io.mateu.uidl.interfaces.CrudAdapter;
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
@Scope("prototype")
@RequiredArgsConstructor
public class LanguageCrudAdapter implements CrudAdapter<
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
    public ListingData<LanguageRow> search(String searchText,
                                           NoFilters filters,
                                           Pageable pageable, HttpRequest httpRequest) {
        return queryService.findAll(searchText, filters, pageable);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deleteLanguageUseCase.handle(new DeleteLanguageCommand(selectedIds));
    }

    @Override
    public LanguageViewModel getView(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public LanguageViewModel getEditor(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService
                .getById(id)
                .orElseThrow());
    }

    @Override
    public LanguageViewModel getCreationForm(HttpRequest httpRequest) {
        return viewModel;
    }
}
