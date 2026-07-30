package io.mateu.workflow.controlplaneservice.infra.in.ui.pages.page;

import io.mateu.core.infra.declarative.orchestrators.crud.Crud;
import io.mateu.uidl.annotations.ListToolbarButton;
import io.mateu.uidl.annotations.Title;
import io.mateu.uidl.data.ListingData;
import io.mateu.uidl.data.NoFilters;
import io.mateu.uidl.data.SearchRequest;
import io.mateu.uidl.interfaces.HttpRequest;
import io.mateu.workflow.controlplaneservice.application.query.PageQueryService;
import io.mateu.workflow.controlplaneservice.application.query.dto.PageRow;
import io.mateu.workflow.controlplaneservice.application.usecases.page.delete.DeletePageCommand;
import io.mateu.workflow.controlplaneservice.application.usecases.page.delete.DeletePageUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Scope("prototype")
@Title("Pages")
public class PageCrudOrchestrator extends Crud<
        PageViewModel,
        PageViewModel,
        PageViewModel,
        NoFilters,
        PageRow,
        String
        > {

    final ImportPagesForm importPagesForm;
    final PageViewModel viewModel;
    final DeletePageUseCase deletePageUseCase;
    final PageQueryService queryService;

    @Override
    public ListingData<PageRow> search(SearchRequest request, HttpRequest httpRequest) {
        return queryService.findAll(request.searchText(), filters(request), request.pageable());
    }

    @Override
    public PageViewModel view(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public PageViewModel edit(String id, HttpRequest httpRequest) {
        return viewModel.load(queryService.getById(id).orElseThrow());
    }

    @Override
    public PageViewModel creationForm(HttpRequest httpRequest) {
        return viewModel;
    }

    @Override
    public String save(HttpRequest httpRequest) {
        var editor = httpRequest.getComponentState(PageViewModel.class);
        editor.save(httpRequest);
        return editor.id();
    }

    @Override
    public String create(HttpRequest httpRequest) {
        var form = httpRequest.getComponentState(PageViewModel.class);
        return form.create(httpRequest);
    }

    @Override
    public void deleteAllById(List<String> selectedIds, HttpRequest httpRequest) {
        deletePageUseCase.handle(new DeletePageCommand(selectedIds));
    }

    @ListToolbarButton(rowsSelectedRequired = false, confirmationRequired = false)
    ImportPagesForm importPages() {
        return importPagesForm;
    }
}
